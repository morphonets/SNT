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

import bdv.img.imaris.Imaris;
import bdv.spimdata.SpimDataMinimal;
import bdv.spimdata.XmlIoSpimDataMinimal;
import bdv.tools.HelpDialog;
import bdv.tools.InitializeViewerState;
import bdv.tools.brightness.ConverterSetup;
import bdv.util.AxisOrder;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.generic.AbstractSpimData;
import bdv.viewer.Source;
import bdv.viewer.animate.SimilarityTransformAnimator;
import bdv.viewer.state.SourceGroup;
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
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.view.Views;
import org.jdom2.JDOMException;
import org.scijava.util.ColorRGB;
import sc.fiji.snt.*;
import sc.fiji.snt.BookmarkManager;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.util.*;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.io.File;
import java.io.IOException;
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
    private double[] cal; // Pixel size of the volume being rendered
    private long[] dims; // Dimensions in pixels of volume being rendered
    private final List<BvvMultiSource> multiSources = new ArrayList<>(); // grouped multi-channel/multi-image sources
    private BookmarkManager markerManager; // lazily initialised on first use

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

    /**
     * Displays the BVV viewer with the specified image.
     *
     * @param <T> the numeric type of the image data
     * @param img the image data to display
     * @param calibration optional calibration values for x, y, z dimensions. If null, defaults to {1, 1, 1}
     * @return the BvvSource representing the displayed image
     */
    @SuppressWarnings("unused")
    public <T extends RealType<T>> BvvSource show(final RandomAccessibleInterval<T> img, final double... calibration) {
        cal = (calibration == null) ? new double[]{1, 1, 1} : calibration;
        dims = new long[]{img.dimension(0), img.dimension(1), img.dimension(2)};
        final BvvOptions opt = (bvvHandle != null ? bvv.vistools.Bvv.options().addTo(bvvHandle) : options)
                .sourceTransform(cal);
        final BvvSource source = BvvFunctions.show(img, "SNT Bvv", opt);
        if (bvvHandle == null) bvvHandle = source.getBvvHandle();
        attachControlPanel(source);
        return source;
    }

    public <T extends RealType<T>> BvvSource show(final ImgPlus<T> imgPlus) {
        // Multi-channel: use native hyperSlice path to avoid the Views.permute →
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
        checkVolumeSize(dims[0], dims[1], dims[2]);
        // Bypass show(RAI, cal) to preserve the image name and assign the source to a named group
        final BvvOptions opt = (bvvHandle != null ? bvv.vistools.Bvv.options().addTo(bvvHandle) : options)
                .sourceTransform(cal);
        final String title = (imgPlus.getName() != null && !imgPlus.getName().isBlank())
                ? imgPlus.getName() : "SNT Bvv";
        final BvvStackSource<?> source = BvvFunctions.show(imgPlus, title, opt);
        if (bvvHandle == null) bvvHandle = source.getBvvHandle();
        attachControlPanel(source);
        return source;
    }

    /**
     * Displays a multi-channel {@link ImgPlus} by extracting each channel as an
     * independent 3D XYZ source via {@link Views#hyperSlice}, avoiding the
     * dimension-ordering ambiguity of {@link net.imglib2.img.display.imagej.ImageJFunctions#wrap}.
     * Mirrors the logic of {@link #showImagePlusMultiChannel(ImagePlus)}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
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
        checkVolumeSize(dims[0], dims[1], nZ);

        final String imageName = (imgPlus.getName() != null && !imgPlus.getName().isBlank())
                ? imgPlus.getName() : "SNT Bvv";

        // Shared option builder: produces the log and handles prefs/blockSize
        final BvvOptions baseOpt = configureBvvOptionsForImage(nZ, nC)
                .axisOrder(AxisOrder.XYZ)
                .sourceTransform(cal);

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
            final BvvStackSource<?> chSource = BvvFunctions.show(
                    (RandomAccessibleInterval) channelRai, chTitle, chOpt);

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
                    final double[] cam = computeCamParams(sx, sy, sz, nZ, Math.max(dims[0], dims[1]));
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

        // Group assignment: must run after all channels are added
        final int groupIdx = multiSources.size();
        final int startIdx = bvvHandle.getViewerPanel().state().getSources().size()
                - (followerSources.size() + 1);
        assignToNamedGroup(imageName, groupIdx, startIdx, followerSources.size() + 1,
                bvvHandle.getViewerPanel());
        final BvvMultiSource multi = new BvvMultiSource(leaderSource, followerSources);
        multiSources.add(multi);
        return multi;
    }

    /**
     * Displays a list of {@link ImagePlus} volumes, each as a {@link BvvMultiSource}
     * (multi-channel images have their channels grouped and transformed together).
     * All images are added to the same BVV window.
     *
     * @param imps the images to display
     * @return list of {@link BvvMultiSource}, one per image
     */
    private List<BvvMultiSource> showImagePlus(final List<ImagePlus> imps) {
        final List<BvvMultiSource> results = new ArrayList<>();
        for (final ImagePlus imp : imps) {
            showImagePlus(imp); // always appends to multiSources (single- and multi-channel paths)
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
     * @param <T>    the numeric type
     * @param imgs   the volumes to display
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
            // Call BvvFunctions.show() directly to avoid casting the BvvSource return of show(RAI, cal)
            final BvvStackSource<?> src = BvvFunctions.show(imgs.get(i), "SNT Bvv " + (i + 1),
                    (bvvHandle != null ? bvv.vistools.Bvv.options().addTo(bvvHandle) : options).sourceTransform(this.cal));
            if (bvvHandle == null) bvvHandle = src.getBvvHandle();
            final BvvMultiSource multi = new BvvMultiSource(src);
            multiSources.add(multi);
            results.add(multi);
        }
        return results;
    }

    /**
     * Returns all {@link BvvMultiSource} groups currently managed by this viewer.
     * This includes multi-channel images and any grouped multi-image sources.
     *
     * @return unmodifiable list of {@link BvvMultiSource} groups
     */
    public List<BvvMultiSource> getMultiSources() {
        return Collections.unmodifiableList(multiSources);
    }

    /**
     * Script-friendly method for setting per-channel colors
     *
     * @param colorNames  color representations (HTML/css values, or hex)
     * @see #setChannelColors(Color...)
     */
    public void setChannelColors(final String... colorNames) {
        setChannelColors(Arrays.stream(colorNames).map(SNTColor::fromString).toArray(Color[]::new));
    }

    /**
     * Sets per-channel colors for a specific source group. Each color is applied
     * to the corresponding channel in order; extra colors are ignored, and channels
     * without a supplied color are left unchanged.
     * <p>
     * Typical Groovy/PySNT usage:
     * <pre>
     *   def bvv = Bvv.open([reference, moving])
     *   bvv.setChannelColors(bvv.getMultiSources().get(0), Color.RED, Color.GREEN, Color.BLUE)
     * </pre>
     * </p>
     *
     * @param group  the target source group, obtained from {@link #getMultiSources()}
     * @param colors one color per channel, in channel order
     */
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
     * R/G/B and image 2 gets C/M/Y. With a single multi-channel image or a
     * dataset where each setup is its own group (e.g. IMS), colors are assigned
     * one-to-one in load order.
     * <p>
     * To apply the same color pattern to every group independently, call
     * {@link #setChannelColors(BvvMultiSource, Color...)} per group.
     * </p>
     * <p>
     * Typical Groovy/PySNT usage:
     * <pre>
     *   def bvv = Bvv.open(img)
     *   bvv.setChannelColors(Color.RED, Color.GREEN, Color.BLUE)
     * </pre>
     * </p>
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
        if (markerManager == null) markerManager = new BookmarkManager(this);
        return markerManager;
    }


    /**
     * Returns the {@link ConverterSetup} list for all channels in a
     * {@link BvvMultiSource}, in channel order. Uses the viewer's internal group
     * state to determine which source indices belong to the group.
     */
    private List<ConverterSetup> getConverterSetups(final BvvMultiSource group) {
        final int groupIdx = multiSources.indexOf(group);
        if (groupIdx < 0 || bvvHandle == null) return Collections.emptyList();
        try {
            final java.lang.reflect.Field stateField = VolumeViewerPanel.class.getDeclaredField("state");
            stateField.setAccessible(true);
            final bdv.viewer.state.ViewerState internalState =
                    (bdv.viewer.state.ViewerState) stateField.get(bvvHandle.getViewerPanel());
            final List<Integer> sourceIds = new ArrayList<>(
                    internalState.getSourceGroups().get(groupIdx).getSourceIds());
            Collections.sort(sourceIds);
            final List<ConverterSetup> allSetups = bvvHandle.getSetupAssignments().getConverterSetups();
            final List<ConverterSetup> result = new ArrayList<>(sourceIds.size());
            for (final int id : sourceIds) {
                if (id < allSetups.size()) result.add(allSetups.get(id));
            }
            return result;
        } catch (final Exception ex) {
            SNTUtils.log("Could not retrieve converter setups for group " + groupIdx + ": " + ex.getMessage());
            return Collections.emptyList();
        }
    }



    /**
     * Convenience factory: creates a standalone BVV viewer and displays one or more
     * {@link ImagePlus} volumes in one step. Multi-channel images have their channels
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
                default   -> throw new IllegalArgumentException("Unsupported type: " + item.getClass().getName());
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
     * <pre>
     *   import bdv.spimdata.XmlIoSpimDataMinimal
     *   spimData = new XmlIoSpimDataMinimal().load("/path/to/dataset.xml")
     *   def bvv = Bvv.open(spimData)
     * </pre>
     * or via Bio-Formats/IMS loaders that produce a {@link AbstractSpimData} with
     * a multiresolution pyramid.
     * </p>
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
            if (source instanceof AbstractSpimData)
                bvv.show((AbstractSpimData<?>) source);
            else {
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
     */
    private static Object resolvePathToSource(final String filePathOrUrl) {
        final File file = new File(filePathOrUrl);
        final String lower = file.getName().toLowerCase();

        if (lower.endsWith(".ims")) {
            final String basePath = file.getAbsolutePath();
            final String xmlPath = basePath.substring(0, basePath.length() - 4) + ".xml";
            try {
                if (new File(xmlPath).exists()) {
                    SNTUtils.log("BVV: reusing existing XML sidecar: " + xmlPath);
                    return new XmlIoSpimDataMinimal().load(xmlPath);
                }
                final File dir = file.getParentFile();
                if (dir != null && !dir.canWrite()) {
                    throw new IllegalArgumentException(
                            "Cannot write to directory: " + dir.getAbsolutePath() + "\n" +
                                    "Create the BDV XML file manually via " +
                                    "Plugins > BigDataViewer > Create XML for Imaris file, " +
                                    "then use Bvv.open(\"/path/to/dataset.xml\").");
                }
                final SpimDataMinimal spimData = Imaris.openIms(file.getAbsolutePath());
                new XmlIoSpimDataMinimal().save(spimData, xmlPath);
                // Patch placeholder setup names before loading: setName() is protected
                // in BasicViewSetup so we patch the XML file directly instead.
                final String base = file.getName().endsWith(".ims")
                        ? file.getName().substring(0, file.getName().length() - 4)
                        : file.getName();
                patchImsXml(xmlPath, base);
                SNTUtils.log("BVV: created XML sidecar: " + xmlPath);
                return new XmlIoSpimDataMinimal().load(xmlPath);
            } catch (final IOException | SpimDataException e) {
                throw new IllegalArgumentException("Could not open IMS file: " + e.getMessage(), e);
            }
        }

        if (lower.endsWith(".xml")) {
            try {
                return new XmlIoSpimDataMinimal().load(filePathOrUrl);
            } catch (final SpimDataException e) {
                throw new IllegalArgumentException("Could not open XML file: " + e.getMessage(), e);
            }
        }

        // Fallback: open as ImgPlus (includes size check before reaching BVV)
        final ImgPlus<?> img = ImgUtils.open(filePathOrUrl);
        if (img == null)
            throw new IllegalArgumentException("Could not open file: " + filePathOrUrl);
        return img;
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
                    }
                }
            } catch (final Exception ignored) {} // defensive: never break rendering for a metadata hiccup
        }

        // Derive a display name from the SpimData base path
        String datasetName = null;
        try {
            if (spimData.getBasePathURI() != null)
                datasetName = new File(spimData.getBasePathURI()).getName();
        } catch (final Exception ignored) {}
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
        return Collections.singletonList(multi);
    }

    private static AxisOrder getAxisOrder(final ImagePlus imp) {
        final boolean hasZ = imp.getNSlices()   > 1;
        final boolean hasC = imp.getNChannels() > 1;
        final boolean hasT = imp.getNFrames()   > 1;
        if (!hasZ && !hasC && !hasT) return AxisOrder.XY;
        if ( hasZ && !hasC && !hasT) return AxisOrder.XYZ;
        if (!hasZ &&  hasC && !hasT) return AxisOrder.XYC;
        if (!hasZ && !hasC &&  hasT) return AxisOrder.XYT;
        if ( hasZ &&  hasC && !hasT) return AxisOrder.XYZC;
        if (!hasZ &&  hasC &&  hasT) return AxisOrder.XYCT;
        if ( hasZ && !hasC &&  hasT) return AxisOrder.XYZT;
        return AxisOrder.XYZCT; // hasZ && hasC && hasT
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
            case 8 -> 255;
            case 16 -> 65535;
            default -> 65535; // 32-bit float handled by toUnsignedShortIfFloat, others fallback to 16-bit range
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
        final BvvOptions opt = (bvvHandle != null ? bvv.vistools.Bvv.options().addTo(bvvHandle) : options)
                .sourceTransform(cal);
        final BvvSource source = BvvFunctions.show(
                ImgUtils.toUnsignedShortIfFloat(data, minMax[0], minMax[1]),
                label, opt);
        source.setDisplayRange(minMax[0], minMax[1]);
        if (bvvHandle == null) bvvHandle = source.getBvvHandle();
        attachControlPanel(source);
        return source;
    }

    private BvvOptions configureBvvOptionsForImage(final ImagePlus imp) {
        return configureBvvOptionsForImage(imp.getNSlices(), imp.getNChannels());
    }

    private BvvOptions configureBvvOptionsForImage(final long nSlices, final int nChannels) {
        final int blockSize = nSlices <= 32 ? 32 : nSlices <= 64 ? 64 : 128;
        // Read render quality preferences (set via Camera Controls options menu)
        final SNTPrefs prefs = (snt != null) ? snt.getPrefs() : null;
        final int renderW = parseIntPref(prefs, SNTPrefs.BVV_RENDER_WIDTH, 512);
        final int renderH = parseIntPref(prefs, SNTPrefs.BVV_RENDER_HEIGHT, 512);
        final int maxMillis = parseIntPref(prefs, SNTPrefs.BVV_MAX_RENDER_MILLIS, 30);
        final double maxStep = parseDoublePref(prefs, SNTPrefs.BVV_MAX_STEP_IN_VOXELS, 1.0);
        SNTUtils.log(String.format(
                "BVV: %d slices, %d ch → blockSize=%d renderRes=%dx%d maxMillis=%d maxStep=%.1f",
                nSlices, nChannels, blockSize, renderW, renderH, maxMillis, maxStep));
        return bvv.vistools.Bvv.options()
                .preferredSize(1024, 1024)
                .frameTitle("SNT BVV")
                .maxCacheSizeInMB(300)
                .ditherWidth(1)
                .cacheBlockSize(blockSize)
                .numDitherSamples(1)
                .renderWidth(renderW)
                .renderHeight(renderH)
                .maxRenderMillis(maxMillis)
                .maxAllowedStepInVoxels(maxStep);
    }

    private static int parseIntPref(final SNTPrefs prefs, final String key, final int def) {
        if (prefs == null) return def;
        try {
            return Integer.parseInt(prefs.getTemp(key, String.valueOf(def)));
        } catch (final NumberFormatException ignored) {
            return def;
        }
    }

    private static double parseDoublePref(final SNTPrefs prefs, final String key, final double def) {
        if (prefs == null) return def;
        try {
            return Double.parseDouble(prefs.getTemp(key, String.valueOf(def)));
        } catch (final NumberFormatException ignored) {
            return def;
        }
    }

    @SuppressWarnings("UnusedReturnValue")
    private BvvSource showImagePlus(final ImagePlus imp) {
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
        final BvvOptions opt = configureBvvOptionsForImage(imp)
                .axisOrder(getAxisOrder(imp))
                .sourceTransform(cal);
        final BvvStackSource<?> source = switch (imp.getType()) {
            case ImagePlus.COLOR_256 -> throw new IllegalArgumentException("Unsupported image type (COLOR_256).");
            case ImagePlus.GRAY8 -> BvvFunctions.show(ImageJFunctions.wrapByte(imp), imp.getTitle(), opt);
            case ImagePlus.GRAY16 -> BvvFunctions.show(ImageJFunctions.wrapShort(imp), imp.getTitle(), opt);
            //case ImagePlus.GRAY32 -> throw new IllegalArgumentException("32 bit images are not supported");
            default -> BvvFunctions.show(ImageJFunctions.wrapRGBA(imp), imp.getTitle(), opt);
        };
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
        // Each channelImp has nChannels=1; derive axis order from Z and T only
        final AxisOrder channelAxisOrder = imp.getNSlices() == 1 && imp.getNFrames() == 1 ? AxisOrder.XY
                : imp.getNSlices() > 1 && imp.getNFrames() > 1 ? AxisOrder.XYZT
                : imp.getNSlices() > 1 ? AxisOrder.XYZ
                : AxisOrder.XYT;
        final BvvOptions baseOpt = configureBvvOptionsForImage(imp)
                .axisOrder(channelAxisOrder)
                .sourceTransform(cal);
        // Determine once whether we're adding to an existing window
        final boolean hasExistingWindow = bvvHandle != null;
        BvvStackSource<?> leaderSource = null;
        final List<BvvStackSource<?>> followerSources = new ArrayList<>();
        for (int c = 1; c <= imp.getNChannels(); c++) {
            final ImagePlus channelImp = ImpUtils.getChannel(imp, c);
            if (ImagePlus.GRAY32 == channelImp.getType()) ImpUtils.convertTo16bit(channelImp);
            final String title = imp.getTitle() + " (Ch" + c + ")";
            // Channel 1 of first image: use baseOpt to create window
            // All other channels and all channels of subsequent images: addTo existing handle.
            // When this ternary reaches the else branch, leaderSource is guaranteed non-null
            // (set in the previous iteration) and bvvHandle may still be null only on the
            // very first image's first channel which takes the true branch instead.
            final BvvOptions chOpt = (!hasExistingWindow && leaderSource == null)
                    ? baseOpt
                    : baseOpt.addTo(bvvHandle != null ? bvvHandle : leaderSource.getBvvHandle());
            final BvvStackSource<?> chSource = switch (channelImp.getType()) {
                case ImagePlus.GRAY8 -> BvvFunctions.show(ImageJFunctions.wrapByte(channelImp), title, chOpt);
                case ImagePlus.GRAY16 -> BvvFunctions.show(ImageJFunctions.wrapShort(channelImp), title, chOpt);
                default -> BvvFunctions.show(ImageJFunctions.wrapRGBA(channelImp), title, chOpt);
            };
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
        return multi;
    }

    /**
     * Creates a named {@link SourceGroup} in the viewer containing all channels
     * of one image, replacing the auto-generated "group N" assignment.
     */
    @SuppressWarnings("deprecation")
    private void assignToNamedGroup(final String name,
                                    final int groupIdx,
                                    final int startIdx,
                                    final int numChannels,
                                    final VolumeViewerPanel viewerPanel) {
        if (viewerPanel == null) return;
        // HACK: BVV has two parallel state APIs: the public SynchronizedViewerState (state())
        // and the internal bdv.viewer.state.ViewerState state field. The 'Groups' panel
        // renders from the internal state only. viewerPanel.addGroup() calls do not
        // populate the panel since it is initialized from the group count at construction.
        // The only working approach is to mutate the pre-allocated internal groups directly
        // via reflection and mirror VolumeViewerPanel state.getSourceGroups().get(i).addSource(i)
        try {
            final java.lang.reflect.Field stateField = VolumeViewerPanel.class.getDeclaredField("state");
            stateField.setAccessible(true);
            final bdv.viewer.state.ViewerState internalState = (bdv.viewer.state.ViewerState) stateField.get(viewerPanel);
            final List<SourceGroup> groups = internalState.getSourceGroups();
            if (groupIdx < groups.size()) {
                final SourceGroup group = groups.get(groupIdx);
                final int endIdx = startIdx + numChannels - 1;
                for (int i = startIdx; i <= endIdx; i++) group.addSource(i);
                SNTUtils.log("BVV group [" + groupIdx + "] '" + name + "': sources " + startIdx + "–" + endIdx);
            }
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
            pathOverlay.updatePaths();
            final VolumeViewerFrame bvvFrame = bvv.getViewerFrame();
            final BvvActions actions = new BvvActions(bvv);
            // "Source Transforms" card: added first so it appears just below the Groups card.
            // Collapsed by default, so it is out of the way
            bvvFrame.getCardPanel().addCard("Source Transforms", sourceTransformsToolbar(actions), false);
            bvvFrame.getCardPanel().addCard("Camera Controls",
                    new CameraControls(this, source, pathOverlay.overlayRenderer).getToolbar(actions), true);
            bvvFrame.getCardPanel().addCard("SNT Annotations", sntToolbar(actions), true);
            // Register M key to place a marker at the current mouse position
            final javax.swing.InputMap imap = bvvFrame.getViewerPanel()
                    .getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
            final javax.swing.ActionMap amap = bvvFrame.getViewerPanel().getActionMap();
            imap.put(javax.swing.KeyStroke.getKeyStroke('m'), "snt-add-marker");
            amap.put("snt-add-marker", actions.addMarkerAction());
            SwingUtilities.invokeLater(bvv::expandAndFocusCardPanel);
        }
        // Initialize brightness from data percentiles (BVV doesn't do this automatically)
        SwingUtilities.invokeLater(() ->
                InitializeViewerState.initBrightness(0.001, 0.999,
                        bvv.getViewer().state(), bvv.getViewer().getConverterSetups()));
    }

    /**
     * Computes appropriate camera parameters from image physical dimensions.
     * BVV camera params are in units of screen pixel width; after initTransform
     * the image is scaled to fill the window (~1024px), so we derive depth
     * extent in that coordinate space.
     *
     * @param imp the image being displayed
     * @return double[] {dCam, dClipNear, dClipFar}
     */
    double[] computeCamParams(final ImagePlus imp) {
        if (imp == null) return new double[]{2000, 1000, 1000};
        final double pw = (cal != null && cal[0] > 0) ? cal[0] : imp.getCalibration().pixelWidth;
        final double ph = (cal != null && cal[1] > 0) ? cal[1] : imp.getCalibration().pixelHeight;
        final double pd = (cal != null && cal[2] > 0) ? cal[2] : imp.getCalibration().pixelDepth;
        return computeCamParams(pw, ph, pd, imp.getNSlices(), Math.max(imp.getWidth(), imp.getHeight()));
    }

    /**
     * Computes appropriate camera parameters from raw physical dimensions.
     * Shared by {@link #computeCamParams(ImagePlus)} and {@link #showImgPlusMultiChannel}.
     *
     * @param sx    pixel width (calibrated)
     * @param sy    pixel height (calibrated)
     * @param sz    pixel depth (calibrated)
     * @param nZ    number of Z slices
     * @param maxXY largest spatial dimension in pixels (max of width, height)
     * @return double[] {dCam, dClipNear, dClipFar}
     */
    private double[] computeCamParams(final double sx, final double sy, final double sz,
                                      final long nZ, final long maxXY) {
        final double physZ   = nZ * sz;
        final double scale   = 1024.0 / maxXY;
        final double zExtent = (physZ / ((sx + sy) / 2)) * scale;
        final double dCam    = Math.max(2000, zExtent * 2.5);
        final double dClip   = Math.max(1000, zExtent * 1.5);
        SNTUtils.log(String.format("BVV camParams: physZ=%.1f zExtent=%.1f → dCam=%.0f dClip=%.0f",
                physZ, zExtent, dCam, dClip));
        return new double[]{dCam, dClip, dClip};
    }

    /**
     * Patches placeholder setup names in a BDV XML sidecar created from an IMS
     * file. {@link Imaris#openIms} writes {@code "(name not specified)"} for every
     * {@code ViewSetup}; this method replaces each occurrence sequentially with
     * {@code "<base> (Ch1)"}, {@code "<base> (Ch2)"}, etc. by directly editing the
     * XML file. Patching the file (rather than the in-memory object) is necessary
     * because setName() in {@link mpicbg.spim.data.generic.sequence.BasicViewSetup}
     * is protected.
     *
     * @param xmlPath path to the sidecar XML file to patch
     * @param base    base name to use (typically the IMS filename without extension)
     * @throws IOException if the file cannot be read or written
     */
    private static void patchImsXml(final String xmlPath, final String base) throws IOException {
        final java.nio.file.Path path = java.nio.file.Paths.get(xmlPath);
        String xml = java.nio.file.Files.readString(path);
        int ch = 1;
        while (xml.contains("(name not specified)"))
            xml = xml.replaceFirst("\\(name not specified\\)", base + " (Ch" + ch++ + ")");
        java.nio.file.Files.writeString(path, xml);
    }

    /**
     * Checks whether a volume's per-channel voxel count is within BVV's texture
     * capacity. BVV's {@code DefaultSimpleStackManager} computes the texture buffer
     * size as {@code width * height * depth * 2} using a 32-bit signed int; values
     * beyond ~1 billion voxels cause integer overflow and a fatal GL crash.
     *
     * @throws IllegalArgumentException if the volume exceeds ~1 Gvox/channel
     */
    private static void checkVolumeSize(final long width, final long height, final long depth) {
        // BVV allocates width*height*depth*2 bytes as a signed int; max safe value is
        // Integer.MAX_VALUE = 2^31-1, so the voxel limit is (2^31-1)/2 ≈ 1.07 Gvox.
        final long voxels = width * height * depth;
        if (voxels * 2L > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(String.format(
                    "Volume too large for BVV's texture manager: %dx%dx%d = %.2f Gvox/channel " +
                            "(limit ~1.07 Gvox). For tiled datasets, open the native " +
                            "BDV/HDF5 or IMS source directly to use BVV's pyramid-aware cache.",
                    width, height, depth, voxels / 1e9));
        }
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
        final JToggleButton liveSyncToggle = GuiUtils.Buttons.toolbarToggleButton(
                actions.toggleLiveSyncAction(multiSources),
                "<html>Toggle live transform sync across grouped sources.<br>"
                        + "When off, transforms are only propagated on demand.",
                IconFactory.GLYPH.LINK, IconFactory.GLYPH.UNLINK);
        liveSyncToggle.setSelected(true);
        toolbar.add(liveSyncToggle);
        toolbar.add(GuiUtils.Buttons.toolbarButton(actions.syncTransformsAction(multiSources),
                "<html>Apply now the current transform of each group lead<br>to all sources in the group."));
        toolbar.addSeparator();
        toolbar.add(Box.createHorizontalGlue());
        toolbar.addSeparator();
        toolbar.add(GuiUtils.Buttons.toolbarButton(actions.exportTransformedSourceAction(multiSources),
                "<html>Apply the manual transform to a source image and export the result as a TIFF.<br>"
                        + "The image is resampled onto the pixel grid of a chosen reference source."));
        toolbar.addSeparator();
        toolbar.add(GuiUtils.Buttons.toolbarButton(actions.saveTransformAction(multiSources),
                "<html>Save manual transform of a source group to an XML file."));
        toolbar.add(GuiUtils.Buttons.toolbarButton(actions.loadTransformAction(multiSources),
                "<html>Load a previously saved manual transform and apply it to a source group."));
        toolbar.add(Box.createHorizontalGlue());
        toolbar.addSeparator();
        toolbar.add(GuiUtils.Buttons.help("https://gist.github.com/tferr/2f4dbb7c52df154a6e14a1fecd1e785a"));
        return toolbar;
    }

    private JToolBar sntToolbar(final BvvActions actions) {
        final JToolBar toolbar = createToolbar();
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
        final JToggleButton markerButton = GuiUtils.Buttons.toolbarToggleButton(
                actions.showMarkerManagerAction(),
                "<html>Show/hide the Marker Manager.<br>"
                        + "Press M in the viewer to place a marker at the cursor position.",
                IconFactory.GLYPH.MARKER, IconFactory.GLYPH.MARKER);
        // Keep button state in sync with frame visibility
        getMarkerManager().getBvvPanel().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentShown(java.awt.event.ComponentEvent e) { markerButton.setSelected(true); }
            @Override public void componentHidden(java.awt.event.ComponentEvent e) { markerButton.setSelected(false); }
        });
        toolbar.add(markerButton);
        toolbar.addSeparator();
        final JButton optionsButton = optionsButton(actions);
        toolbar.add(optionsButton);
        return toolbar;
    }

    private JButton optionsButton(final BvvActions actions) {
        final JPopupMenu menu = new JPopupMenu();
        final JButton oButton = GuiUtils.Buttons.OptionsButton(IconFactory.GLYPH.TOOL, 1f, menu);
        menu.add(new JMenuItem(actions.importAction()));
        menu.addSeparator();
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
            target.set(scale, 0, 0); target.set(scale, 1, 1); target.set(scale, 2, 2);
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
            SNTUtils.error("Could not retrieve panel bounds", e);
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
    private class CameraControls {

        private final Bvv bvvInstance;
        private final OverlayRenderer overlayRenderer;
        private final JSpinner dCamSpinner;
        private final JSpinner nearSpinner;
        private final JSpinner farSpinner;
        private final double[] initialCamParams;

        CameraControls(final Bvv bvvInstance, final BvvSource source, final OverlayRenderer overlayRenderer) {
            this.bvvInstance = bvvInstance;
            this.overlayRenderer = overlayRenderer;
            // On the first render with valid canvas dimensions, compute a centered
            // fit-to-viewport transform, apply it, and store it for reset.
            // This is necessary because initTransform in attachControlPanel runs
            // before the panel is laid out (width/height are ~1px at that point).
            initialCamParams = new double[]{overlayRenderer.dCam, overlayRenderer.nearClip, overlayRenderer.farClip};
            // Adaptive spinner ranges: max = 5× the initial dCam, step = dCam/20
            final int dCamMax = (int) Math.max(10000, initialCamParams[0] * 5);
            final int clipMax = (int) Math.max(10000, initialCamParams[1] * 5);
            final int dCamStep = (int) Math.max(50, initialCamParams[0] / 20);
            final int clipStep = (int) Math.max(50, initialCamParams[1] / 20);
            this.dCamSpinner = GuiUtils.integerSpinner((int) overlayRenderer.dCam, 10, dCamMax, dCamStep, true);
            this.nearSpinner = GuiUtils.integerSpinner((int) overlayRenderer.nearClip, 10, clipMax, clipStep, true);
            this.farSpinner = GuiUtils.integerSpinner((int) overlayRenderer.farClip, 10, clipMax, clipStep, true);
            setupSpinners();
        }

        private double[] defaultCamParams() {
            return initialCamParams; // image-derived values, not hardwired
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
            return new AbstractAction("Reset", IconFactory.menuIcon(IconFactory.GLYPH.UNDO)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final VolumeViewerPanel viewerPanel = bvvInstance.currentBvv.getViewer();
                    final AffineTransform3D current = new AffineTransform3D();
                    viewerPanel.state().getViewerTransform(current);
                    final int cw = viewerPanel.getDisplay().getWidth();
                    final int ch = viewerPanel.getDisplay().getHeight();
                    // Compute a fit-to-viewport transform directly from image geometry
                    // initTransform (BDV) is incompatible with BVV's perspective projection
                    final AffineTransform3D target;
                    if (cal != null && dims != null && cw > 0 && ch > 0) {
                        final double px = cal[0] > 0 ? cal[0] : 1;
                        final double py = cal[1] > 0 ? cal[1] : 1;
                        final double pz = cal[2] > 0 ? cal[2] : 1;
                        final double physW = dims[0] * px;
                        final double physH = dims[1] * py;
                        final double physZ = dims[2] * pz;
                        // Scale to fit the largest physical XY dimension into the canvas
                        final double scale = Math.min(cw / physW, ch / physH);
                        // Center XY; place Z center at screen Z=0
                        target = new AffineTransform3D();
                        target.set(scale, 0, 0);
                        target.set(scale, 1, 1);
                        target.set(scale, 2, 2);
                        target.set(cw / 2.0 - scale * physW / 2.0, 0, 3);
                        target.set(ch / 2.0 - scale * physH / 2.0, 1, 3);
                        target.set(-scale * physZ / 2.0, 2, 3);
                        SNTUtils.log("BVV reset: scale=" + scale + " target=" + target);
                    } else {
                        // Fallback: no cal/dims available, use identity (BVV default view)
                        target = new AffineTransform3D(); // identity
                    }
                    viewerPanel.setTransformAnimator(
                            new SimilarityTransformAnimator(current, target, 0, 0, 200));
                    SwingUtilities.invokeLater(() -> {
                        dCamSpinner.setValue(defaultCamParams()[0]);
                        nearSpinner.setValue(defaultCamParams()[1]);
                        farSpinner.setValue(defaultCamParams()[2]);
                    });
                    updateCameraParameters(false);
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
            final JToolBar toolbar = createToolbar();
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
            final String curW = (prefs != null) ? prefs.getTemp(SNTPrefs.BVV_RENDER_WIDTH, "512") : "512";
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
            final double curStep = parseDoublePref(prefs, SNTPrefs.BVV_MAX_STEP_IN_VOXELS, 1.0);
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
            menu.add(new JMenuItem(resetViewAction()));
            menu.add(new JMenuItem(actions.loadSettingsAction()));
            menu.add(new JMenuItem(actions.saveSettingsAction()));
            addSeparator(menu, IconFactory.GLYPH.INFO, "Help");
            menu.add(new JMenuItem(actions.showHelpAction()));
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
            // Camera params are set externally via VolumeViewerPanel.setCamParams() after image loading;
            // store BVV defaults here as initial values. These will be overridden by computeCamParams()
            dCam = 2000;
            nearClip = 1000;
            farClip = 1000;
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
                    final File f = guiUtils.getFile(getDefaultDir(), "xml");
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
                    final File f = guiUtils.getSaveFile("Save BVV Settings...", getDefaultDir(), "xml");
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
                    final File[] files = guiUtils.getReconstructionFiles(getDefaultDir());
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
                        guiUtils.error(String.format("%d/%d file(s) successfully imported.", (files.length - failureCounter), files.length));
                    }
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
                                (1f == renderingOptions.getThicknessMultiplier())
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
                    // Validate against image bounds: coordinates outside the volume indicate
                    // the view was rotated when M was pressed; the focal-plane projection
                    // is then meaningless. Prompt the user to use a principal-axis view.
                    if (dims != null && cal != null) {
                        final double maxX = dims[0] * cal[0];
                        final double maxY = dims[1] * cal[1];
                        final double maxZ = dims[2] * cal[2];
                        if (x < 0 || y < 0 || z < 0 || x > maxX || y > maxY || z > maxZ) {
                            Toolkit.getDefaultToolkit().beep();
                            bvv.getViewer().showMessage("Outside bounds: Align view to a principal axis");
                            return;
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
                    final HelpDialog hDialog = new HelpDialog(bvv.getViewerFrame());
                    hDialog.setPreferredSize(bvv.getViewerFrame().getCardPanel().getComponent().getPreferredSize());
                    hDialog.setLocationRelativeTo(bvv.getViewerFrame());
                    SwingUtilities.invokeLater(() -> hDialog.setVisible(true));
                }
            };
        }

        /**
         * Manually propagates each group leader's current transform to its followers.
         * Use this when live sync is disabled or when GPU lag makes live sync impractical.
         */
        Action syncTransformsAction(final List<BvvMultiSource> multiSources) {
            return new AbstractAction("Sync Transforms", IconFactory.menuIcon(IconFactory.GLYPH.SYNC)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    multiSources.forEach(BvvMultiSource::syncTransforms);
                    bvv.getViewer().showMessage("Transforms synced");
                }
            };
        }

        Action saveTransformAction(final List<BvvMultiSource> multiSources) {
            return new AbstractAction("Save Transform", IconFactory.menuIcon(IconFactory.GLYPH.EXPORT)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final BvvMultiSource target = chooseMultiSource("Save transform of:");
                    if (target == null) return;
                    final File file = guiUtils.getSaveFile("Save Transform...", getDefaultDir(), "xml");
                    if (file == null) return;
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
                    final File file = guiUtils.getFile(getDefaultDir(), "xml");
                    if (file == null) return;
                    try {
                        applyTransformFile(file, multiSources);
                    } catch (final Exception ex) {
                        guiUtils.error("Could not load transform: " + ex.getMessage());
                    }
                }
            };
        }

        private File getDefaultDir() {
            return (snt != null) ? snt.getPrefs().getRecentDir() : new File(System.getProperty("user.home"));
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
                    final File file = guiUtils.getSaveFile("Export Transformed Image...", getDefaultDir(), "tif");
                    if (file == null) return;
                    final String outPath = (file.getName().endsWith(".tif") || file.getName().endsWith(".tiff"))
                            ? file.getAbsolutePath() : file.getAbsolutePath() + ".tif";

                    bvv.getViewer().showMessage("Exporting...");
                    new Thread(() -> {
                        try {
                            exportTransform(moving, reference, outPath);
                        } catch (final Exception ex) {
                            guiUtils.error("Export failed: " + ex.getMessage());
                            SNTUtils.error("BVV transform export failed", ex);
                        }
                    }, "BVV-TransformExport").start();
                }
            };
        }

        @SuppressWarnings("unchecked")
        private void exportTransform(final BvvMultiSource moving, final BvvMultiSource reference, final String outPath) throws Exception {
            // Moving source: RAI + intrinsic (calibration) transform
            final var movingSac = moving.getLeader().getSources().getFirst();
            final Source<RealType<?>> movingSpim = (Source<RealType<?>>) movingSac.getSpimSource();
            final RandomAccessibleInterval<RealType<?>> movingRai = movingSpim.getSource(0, 0);
            final AffineTransform3D srcToWorld = new AffineTransform3D();
            movingSpim.getSourceTransform(0, 0, srcToWorld);

            // Manual (registration) transform applied on top of the intrinsic one
            final AffineTransform3D manualT = new AffineTransform3D();
            moving.getLeaderTransform(manualT);

            // movingToWorld = manualT ∘ srcToWorld  (moving pixels → world)
            final AffineTransform3D movingToWorld = new AffineTransform3D();
            movingToWorld.set(manualT);
            movingToWorld.concatenate(srcToWorld);

            // Reference source: RAI + calibration transform
            final var refSac = reference.getLeader().getSources().getFirst();
            final Source<RealType<?>> refSpim = (Source<RealType<?>>) refSac.getSpimSource();
            final RandomAccessibleInterval<RealType<?>> refRai = refSpim.getSource(0, 0);
            // Guard against TB-scale output: the export materialises every voxel of the
            // reference grid via LoopBuilder: Abort early with a clear message rather
            // than running for hours or exhausting heap.
            checkVolumeSize(refRai.dimension(0), refRai.dimension(1), refRai.dimension(2));
            final AffineTransform3D refSrcToWorld = new AffineTransform3D();
            refSpim.getSourceTransform(0, 0, refSrcToWorld);

            // totalT maps ref pixels → moving pixels:
            //   totalT = (manualT . srcToWorld)^{-1} . refSrcToWorld
            final AffineTransform3D totalT = movingToWorld.inverse();
            totalT.concatenate(refSrcToWorld);

            // Apply the composed transform and resample onto the reference grid.
            // Raw casts are required because Views.extendZero/interpolate have
            // self-referential bounds <F extends RealType<F>> that the wildcard
            // RealType<?> cannot satisfy at the call site.
            @SuppressWarnings({"unchecked", "rawtypes"}) final RealRandomAccessible<RealType<?>> realRai =
                    (RealRandomAccessible<RealType<?>>) RealViews.affine(
                            Views.interpolate((net.imglib2.RandomAccessible) Views.extendZero(
                                    (RandomAccessibleInterval) movingRai), new NLinearInterpolatorFactory()),
                            totalT);
            final RandomAccessibleInterval<RealType<?>> result = Views.interval(Views.raster(realRai), refRai);
            ImgUtils.save(result, outPath, new UnsignedShortType());
            bvv.getViewer().showMessage("Saved: " + new File(outPath).getName());
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
         * Toggles live transform synchronization across all grouped sources.
         * When live sync is off, use {@link #syncTransformsAction} to apply
         * transforms manually. Useful on slow GPUs with many sources.
         */
        Action toggleLiveSyncAction(final List<BvvMultiSource> multiSources) {
            return new AbstractAction("Live Sync", IconFactory.menuIcon(IconFactory.GLYPH.LINK)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final boolean live = multiSources.isEmpty() || multiSources.getFirst().isLiveSync();
                    multiSources.forEach(ms -> ms.setLiveSync(!live));
                    bvv.getViewer().showMessage("Live transform sync: " + (!live ? "ON" : "OFF"));
                }
            };
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
