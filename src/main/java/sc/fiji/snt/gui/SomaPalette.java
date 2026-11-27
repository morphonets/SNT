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
package sc.fiji.snt.gui;

import ij.ImageListener;
import ij.ImagePlus;
import ij.gui.ImageCanvas;
import ij.gui.Line;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.gui.StackWindow;
import net.imagej.ImgPlus;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.tracing.auto.SomaUtils;
import sc.fiji.snt.util.BigDataUtils;
import sc.fiji.snt.util.ImgUtils;

import javax.swing.*;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.Panel;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Preview wizard for multi-soma detection ({@link SomaUtils#detectAllSomas}). Detection runs live on a single 2D
 * plane (a slice or a max projection) as the parameters are adjusted, and detections are drawn as ovals. In Stream
 * mode the plane is read from a coarse resolution level, to avoid loading the whole volume
 *
 * @author Tiago Ferreira
 */
public class SomaPalette extends WizardPalette {

    /** Largest preview plane (pixels). Larger planes need an ROI (in-memory data) or a coarser level (Stream mode) */
    private static final long MAX_PLANE_PIXELS = 4_000_000L;
    /** Largest volume (voxels) read into memory for streamed data */
    private static final long MAX_PROJECTION_VOXELS = 32_000_000L;
    private static final String SOMA_ROI = "soma";
    // Somata smaller than this (preview pixels) are not realistic: avoids spurious detections out of the box
    private static final double DEFAULT_MIN_RADIUS_PX = 5;
    // User ROIs (solid) and detections (dashed, cyan) must be told apart even if both are ovals
    private static final String USER_ROI = "soma-user";
    private static final Color OVAL_COLOR = Color.CYAN;
    private static final Color USER_ROI_COLOR = Color.MAGENTA;
    private static final BasicStroke DETECTION_STROKE = new BasicStroke(1.5f, BasicStroke.CAP_BUTT,
            BasicStroke.JOIN_MITER, 10f, new float[]{6f, 4f}, 0f);

    /**
     * Detection parameters, in calibrated units
     *
     * @param threshold   intensity threshold (-1: auto, Otsu)
     * @param minRadius   minimum soma radius (0: no filtering)
     * @param minDistance minimum distance between soma centers (0: auto)
     * @param zSlice      0-based slice of the full resolution grid detection runs on, or -1 for a max projection of
     *                    the volume. Meaningless for 2D data
     */
    public record Params(double threshold, double minRadius, double minDistance, int zSlice) {
    }

    /** Receives the outcome of the wizard */
    public interface Listener {
        /** The user chose to apply the parameters. Always followed by {@link #dismissed()} */
        void applied(Params params);

        /** The wizard is gone (applied or not) */
        void dismissed();
    }

    private final Params initial;
    private final int level;
    private final int sliceHint;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger generation = new AtomicInteger();
    private ExecutorService executor;
    private SomaWindow window;
    // Zero-min XYZ data of the preview (null for 2D data), the number of its slices, and the factor between the Z of
    // the full resolution grid and the Z of the preview: z(preview) = (z + 0.5) * zRatio - 0.5
    private RandomAccessibleInterval<?> volume;
    private int nZ = 1;
    private double zRatio = 1;
    private RandomAccessibleInterval<?> flat; // the data, if it is 2D
    private RandomAccessibleInterval<?> mip; // max projection of the volume
    // Scrolling the stack triggers detection
    private final ImageListener sliceListener = new ImageListener() {
        @Override
        public void imageOpened(final ImagePlus imp) {
        }

        @Override
        public void imageClosed(final ImagePlus imp) {
        }

        @Override
        public void imageUpdated(final ImagePlus imp) {
            final SomaWindow w = window;
            if (w != null && imp == paletteImage) w.sliceMayHaveChanged();
        }
    };
    private double[] spacing = {1, 1};
    private double dataMin;
    private double dataMax;
    // Disclosure shown in the wizard if the plane is not the full resolution plane (null otherwise)
    private String planeNote;
    private volatile String summary = " ";
    private volatile String planeLabel = "";


    /**
     * @param snt       the SNT instance
     * @param initial   the parameters the wizard starts with
     * @param sliceHint the 0-based slice (full resolution grid) shown if the initial parameters specify a max
     *                  projection. Negative for the middle slice
     * @param level     Stream mode only: the resolution level (0: full resolution) the preview is read from.
     *                  Negative for the finest one that can be previewed, see {@link #recommendedLevel(List)}
     */
    public SomaPalette(final SNT snt, final Params initial, final int sliceHint, final int level) {
        super(snt);
        this.initial = initial;
        this.sliceHint = sliceHint;
        this.level = level;
        setParent(snt.getUI());
    }

    public void addListener(final Listener l) {
        if (l != null) listeners.add(l);
    }

    public void show() {
        start();
    }

    /** Brings the wizard window (if any) to the front */
    public void focus() {
        final StackWindow w = window;
        if (w != null) SwingUtilities.invokeLater(w::toFront);
    }

    @Override
    protected void build() {
        try {
            WizardUtils.status(snt, "Soma wizard: reading preview plane...");
            final String problem = acquirePlane();
            if (problem != null) {
                error(problem);
                dismiss();
                return;
            }
            buildAndDisplay();
        } catch (final Throwable t) {
            SNTUtils.error(t);
            error("The Soma Detection Wizard could not be created: " + t.getMessage());
            dismiss();
        }
    }

    private void error(final String msg) {
        if (snt.getUI() != null) snt.getUI().error(msg);
        else SNTUtils.error(msg);
    }

    // Reads the data of the preview. Returns a message describing the problem, or null if it is ready
    @SuppressWarnings({"unchecked", "rawtypes"})
    private String acquirePlane() {
        RandomAccessibleInterval<?> data;
        final double[] sp;
        String note = null;
        if (snt.isStreamMode()) {
            final var viewer = (snt.getUI() == null) ? null : snt.getUI().getActiveBigViewer();
            if (viewer == null) return "No active BDV/BVV viewer to read the preview from.";
            final int srcIdx = snt.getChannel() - 1;
            final List<BigDataUtils.LevelInfo> levels = viewer.getSourceLevels(srcIdx);
            if (levels.isEmpty()) return "The image data of the active viewer is not available.";
            // Any plane can be requested, including the max projection: the level must allow reading it all
            final BigDataUtils.LevelInfo level = (this.level < 0) ? levels.get(recommendedLevel(levels))
                    : levels.get(Math.min(this.level, levels.size() - 1));
            final long[] d = level.dims();
            if (d[0] * d[1] > MAX_PLANE_PIXELS || d[0] * d[1] * d[2] > MAX_PROJECTION_VOXELS)
                return "Resolution level " + level.level() + " is too large for a live preview (" + d[0] + " x " + d[1]
                        + " x " + d[2] + " voxels). Choose a coarser resolution level in the prompt, and run the "
                        + "wizard again.";
            final RandomAccessibleInterval<?> raw = viewer.getSourceData(srcIdx, level.level());
            if (raw == null || raw.numDimensions() != 3) return "The image data of the active viewer is not 3D.";
            data = Views.zeroMin(raw);
            sp = new double[]{level.spacing()[0], level.spacing()[1]};
            zRatio = levels.getFirst().spacing()[2] / level.spacing()[2];
            if (level.level() > 0) {
                note = String.format("Preview of a coarse resolution level (level %d: %d x %d px, %s x %s %s per px). "
                                + "Parameters are in calibrated units and also apply to the full resolution data",
                        level.level(), data.dimension(0), data.dimension(1), SNTUtils.formatDouble(sp[0], 2),
                        SNTUtils.formatDouble(sp[1], 2), snt.getSpacingUnits());
            }
        } else {
            final ImgPlus<?> img = snt.getLoadedDataAsImg(false);
            if (img == null) return "No valid image data available.";
            data = Views.zeroMin(img);
            sp = new double[]{snt.getPixelWidth(), snt.getPixelHeight()};
        }

        // In-memory data that is too large: restrict to the ROI (if any) of the image window
        if (data.dimension(0) * data.dimension(1) > MAX_PLANE_PIXELS) {
            final ImagePlus imp = snt.getImagePlus();
            final Roi roi = (imp == null) ? null : imp.getRoi();
            final String size = data.dimension(0) + " x " + data.dimension(1);
            if (snt.isStreamMode() || roi == null || !roi.isArea()) {
                return "The image plane is too large for a live preview (" + size + " px). "
                        + ((snt.isStreamMode()) ? "Choose a coarser resolution level in the prompt"
                        : "Draw a rectangle ROI around a representative region of the image") + ", and run the wizard again.";
            }
            final Rectangle r = roi.getBounds()
                    .intersection(new Rectangle(0, 0, (int) data.dimension(0), (int) data.dimension(1)));
            if (r.isEmpty() || (long) r.width * r.height > MAX_PLANE_PIXELS) {
                return "The ROI is too large for a live preview (maximum is about " + MAX_PLANE_PIXELS / 1_000_000L
                        + " million pixels). Draw a smaller rectangle ROI, and run the wizard again.";
            }
            final long[] min = new long[data.numDimensions()];
            final long[] max = data.maxAsLongArray();
            min[0] = r.x;
            min[1] = r.y;
            max[0] = r.x + r.width - 1L;
            max[1] = r.y + r.height - 1L;
            data = Views.zeroMin(Views.interval(data, min, max));
            note = String.format("Preview restricted to the ROI of the image (x=%d, y=%d, %d x %d px)", r.x, r.y,
                    r.width, r.height);
        }

        spacing = sp;
        planeNote = note;
        final RandomAccessibleInterval<?> range;
        if (data.numDimensions() > 2) {
            // Streamed data is lazy and remote: read it once, so that browsing the stack is fast
            if (snt.isStreamMode()) data = ImgUtils.copyToMemory((RandomAccessibleInterval) data);
            volume = data;
            nZ = (int) data.dimension(2);
            SNTUtils.log("Soma wizard: computing max projection of " + nZ + " planes...");
            mip = ImgUtils.maxProjectionWithDepth((RandomAccessibleInterval) data, 2, data.min(2), data.max(2))
                    .max();
            range = mip;
        } else {
            flat = data;
            range = data;
        }
        // The range of the threshold does not change with the plane: the projection spans all the planes
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (final RealType<?> t : ((RandomAccessibleInterval<? extends RealType<?>>) range)) {
            final double v = t.getRealDouble();
            if (v < lo) lo = v;
            if (v > hi) hi = v;
        }
        dataMin = lo;
        dataMax = (hi > lo) ? hi : lo + 1;

        // A virtual float view: ImageJ browses the stack, without copies
        paletteImage = ImgUtils.raiToImp(ImgUtils.toFloatView((RandomAccessibleInterval) data),
                "Soma Detection Wizard");
        paletteImage.setDisplayRange(dataMin, dataMax);
        final ij.measure.Calibration cal = paletteImage.getCalibration();
        cal.pixelWidth = sp[0];
        cal.pixelHeight = sp[1];
        cal.setUnit(snt.getSpacingUnits());
        return null;
    }

    // Preview slice (0-based) of a slice of the full resolution grid
    private int toPreviewZ(final int z) {
        return Math.clamp(Math.round((z + 0.5) * zRatio - 0.5), 0, nZ - 1);
    }

    // Slice of the full resolution grid (0-based) of a slice of the preview
    private int toFullZ(final int z) {
        return (int) Math.max(0, Math.round((z + 0.5) / zRatio - 0.5));
    }

    // The plane detection runs on (z: slice of the preview, -1 for the max projection)
    private RandomAccessibleInterval<?> planeAt(final int z) {
        if (volume == null) return flat;
        return (z < 0) ? mip : Views.hyperSlice(volume, 2, z);
    }

    /**
     * The finest resolution level with a plane and a volume small enough for the preview, or the coarsest one
     *
     * @param levels the levels of the data, finest first
     * @return the index of the level
     */
    public static int recommendedLevel(final List<BigDataUtils.LevelInfo> levels) {
        for (int i = 0; i < levels.size(); i++) {
            final long[] d = levels.get(i).dims();
            if (d[0] * d[1] <= MAX_PLANE_PIXELS && d[0] * d[1] * d[2] <= MAX_PROJECTION_VOXELS) return i;
        }
        return levels.size() - 1;
    }

    private void buildAndDisplay() {
        // The MIP is managed here (checkbox), not from the options menu
        paletteOptions = new PaletteOptions(paletteImage, snt.getImagePlus(), "SomaDetection_Snapshot")
                .withoutMipItem();
        if (volume != null) {
            final int slice = (initial.zSlice() >= 0) ? initial.zSlice() : (sliceHint >= 0) ? sliceHint
                    : toFullZ(nZ / 2);
            paletteImage.setSlice(1 + toPreviewZ(slice));
        }
        final ImageCanvas canvas = new ImageCanvas(paletteImage);
        canvas.disablePopupMenu(true);
        canvas.setShowCursorStatus(false);
        executor = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "SNT-SomaPalette-detection");
            t.setDaemon(true);
            return t;
        });
        window = new SomaWindow(paletteImage, canvas);
        ImagePlus.addImageListener(sliceListener);
        SwingUtilities.invokeLater(() -> {
            if (window != null && window.isMip()) paletteOptions.showMip(true, 1d);
            requestDetection();
        });
    }

    // Detection runs in the background. Outdated results are dropped
    private void requestDetection() {
        final SomaWindow w = window;
        if (w == null || executor == null) return;
        final Params p = w.current();
        final int zPreview = w.previewZ();
        final int gen = generation.incrementAndGet();
        SwingUtilities.invokeLater(() -> w.showBusy(gen));
        try {
            executor.execute(() -> {
                if (gen != generation.get()) return;
                final List<SomaUtils.SomaResult> results;
                try {
                    results = detect(planeAt(zPreview), p, () -> gen != generation.get());
                } catch (final RuntimeException ex) {
                    SNTUtils.error("Soma wizard: detection failed", ex);
                    return;
                }
                if (gen == generation.get()) SwingUtilities.invokeLater(() -> {
                    if (gen == generation.get()) w.showResults(results, p);
                });
            });
        } catch (final RejectedExecutionException ignored) {
            // wizard was dismissed
        }
    }

    @SuppressWarnings("unchecked")
    private List<SomaUtils.SomaResult> detect(final RandomAccessibleInterval<?> plane, final Params p,
                                              final java.util.function.BooleanSupplier cancelled) {
        final double avg = (spacing[0] + spacing[1]) / 2d;
        final double minRadiusPx = (p.minRadius() > 0) ? p.minRadius() / avg : 0;
        final double minDistancePx = (p.minDistance() > 0) ? p.minDistance() / avg : 0;
        return SomaUtils.detectAllSomas(
                (RandomAccessibleInterval<? extends RealType<?>>) plane, p.threshold(), minRadiusPx, minDistancePx,
                cancelled);
    }

    private void commit() {
        final Params p = window.current();
        new ArrayList<>(listeners).forEach(l -> l.applied(p));
        dismiss();
    }

    @Override
    protected void beforeDismiss() {
        generation.incrementAndGet();
        ImagePlus.removeImageListener(sliceListener);
        if (window != null) {
            window.stopDebounce();
            window.stopCapture();
        }
    }

    @Override
    protected void notifyDismissed() {
        new ArrayList<>(listeners).forEach(Listener::dismissed);
    }

    @Override
    protected StackWindow windowToClose() {
        return window;
    }

    @Override
    protected void afterDismiss() {
        if (executor != null) executor.shutdownNow();
        volume = null;
        flat = null;
        mip = null;
    }

    @Override
    protected void clearListeners() {
        listeners.clear();
    }

    @Override
    protected boolean resetsUiState() {
        return false; // the command that launched the wizard stays open, and manages the state
    }

    private class SomaWindow extends PaletteWindow {
        private static final long serialVersionUID = 1L;
        private static final int STEPS = 1000;
        private final double maxRadius;
        private final double maxDistance;
        private final JSlider thresholdSlider = newSlider();
        private final JSlider radiusSlider = newSlider();
        private final JSlider distanceSlider = newSlider();
        private final JCheckBox autoThreshold = new JCheckBox("Auto");
        private final JCheckBox mipCheck = new JCheckBox("MIP");
        private int lastSlice;
        private final JLabel thresholdValue = valueLabel();
        private final JLabel radiusValue = valueLabel();
        private final JLabel distanceValue = valueLabel();
        private final JLabel statusLabel = new JLabel(" ");
        private JPanel planePanel;
        private JButton applyButton;
        private JToggleButton roiButton;
        private MouseAdapter captureListener;
        private JButton moreButton;
        private final Timer debounce;
        private boolean loading;

        SomaWindow(final ImagePlus imp, final ImageCanvas ic) {
            super(imp, ic, SomaPalette.this::dismiss);
            fitCanvasToScreen(ic, imp);
            lastSlice = imp.getCurrentSlice();
            final double extent = Math.min(imp.getWidth(), imp.getHeight()) * (spacing[0] + spacing[1]) / 2d;
            maxRadius = Math.max(extent / 4d, Math.max(initial.minRadius(),
                    DEFAULT_MIN_RADIUS_PX * (spacing[0] + spacing[1]) / 2d) * 1.5);
            maxDistance = Math.max(extent / 2d, initial.minDistance() * 1.5);
            debounce = new Timer(150, e -> requestDetection());
            debounce.setRepeats(false);

            final Panel root = new Panel(new GridBagLayout());
            root.setBackground(getBackground()); // not the (light) system one: Swing controls sit on it
            final GridBagConstraints c = new GridBagConstraints();
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1.0;
            c.gridx = 0;
            c.gridwidth = GridBagConstraints.REMAINDER;
            // The note wraps at the width of the other rows (which are assembled first, to measure them)
            final JPanel controls = GuiUtils.Panels.section(null, assembleControls());
            final JPanel status = newPanel(new FlowLayout(FlowLayout.LEFT));
            status.add(statusLabel);
            final JPanel buttons = assembleButtonPanel();
            int row = 0;
            if (planeNote != null) {
                final FlowLayout noteLayout = new FlowLayout(FlowLayout.LEFT);
                final int width = Math.max(controls.getPreferredSize().width, buttons.getPreferredSize().width)
                        - 2 * noteLayout.getHgap();
                final JPanel note = newPanel(noteLayout);
                note.add(new JLabel("<html><div width=" + width + "><i>" + planeNote + "</i></div></html>"));
                c.gridy = row++;
                root.add(note, c);
            }
            c.gridy = row++;
            root.add(controls, c);
            c.gridy = row++;
            root.add(status, c);
            c.gridy = row;
            root.add(buttons, c);

            // Starting point only: the command keeps 0 (no limit) as its own default
            final double defaultRadius = DEFAULT_MIN_RADIUS_PX * (spacing[0] + spacing[1]) / 2d;
            load((initial.minRadius() > 0) ? initial : new Params(initial.threshold(), defaultRadius,
                    initial.minDistance(), initial.zSlice()));
            fitCanvasToPanel(root);
            add(root);
            pack();
            WizardUtils.place(this, parent, snt);
            requestFocusInWindow(); // required to trigger keylistener events
        }

        private JSlider newSlider() {
            final JSlider s = new JSlider(0, STEPS, 0);
            s.setFocusable(false); // keyboard shortcuts (zoom, etc.) only work when image canvas has focus
            s.addChangeListener(e -> sliderChanged());
            return s;
        }

        private JLabel valueLabel() {
            final JLabel l = new JLabel("99999.99 " + snt.getSpacingUnits());
            l.setPreferredSize(l.getPreferredSize());
            return l;
        }

        private void sliderChanged() {
            if (loading) return;
            refreshLabels();
            debounce.restart();
        }

        void stopDebounce() {
            debounce.stop();
        }

        private JPanel assembleControls() {
            final JPanel p = newPanel(new GridBagLayout());
            final GridBagConstraints g = new GridBagConstraints();
            g.insets = new Insets(2, 4, 2, 4);
            g.anchor = GridBagConstraints.WEST;
            final String unit = snt.getSpacingUnits();

            autoThreshold.setFocusable(false);
            autoThreshold.setToolTipText("Uses Otsu's method to compute the threshold");
            autoThreshold.addActionListener(e -> sliderChanged());
            thresholdSlider.setToolTipText("Intensity threshold of the preview plane");
            radiusSlider.setToolTipText("<html>Minimum soma radius in " + unit + ".<br>Smaller detections are "
                    + "discarded (0: no filtering)");
            distanceSlider.setToolTipText("<html>Minimum distance between soma centers in " + unit + ".<br>"
                    + "Of two detections closer than this, only the strongest is kept (0: auto)");

            int y = 0;
            if (volume != null) {
                mipCheck.setFocusable(false);
                mipCheck.setToolTipText("<html>Detect on the max projection of the volume (shown in the image).<br>"
                        + "Otherwise, detection runs on the displayed slice");
                mipCheck.addActionListener(e -> planeChanged());
                final JPanel plane = newPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
                planePanel = plane;
                plane.add(mipCheck);
                plane.add(new JLabel("  (otherwise, scroll the stack)"));
                g.gridy = y++;
                g.gridx = 0;
                g.weightx = 0;
                g.fill = GridBagConstraints.NONE;
                p.add(new JLabel("Plane:"), g);
                g.gridx = 1;
                g.gridwidth = 3;
                p.add(plane, g);
                g.gridwidth = 1;
            }
            addRow(p, g, y++, "Threshold:", thresholdSlider, thresholdValue, autoThreshold);
            addRow(p, g, y++, "Min. radius:", radiusSlider, radiusValue, null);
            addRow(p, g, y, "Min. distance:", distanceSlider, distanceValue, null);
            return p;
        }

        private void addRow(final JPanel p, final GridBagConstraints g, final int y, final String label,
                            final JSlider slider, final JLabel value, final JCheckBox extra) {
            g.gridy = y;
            g.gridx = 0;
            g.weightx = 0;
            g.fill = GridBagConstraints.NONE;
            p.add(new JLabel(label), g);
            g.gridx = 1;
            g.weightx = 1;
            g.fill = GridBagConstraints.HORIZONTAL;
            p.add(slider, g);
            g.gridx = 2;
            g.weightx = 0;
            g.fill = GridBagConstraints.NONE;
            p.add(value, g);
            if (extra != null) {
                g.gridx = 3;
                p.add(extra, g);
            }
        }

        private JPanel assembleButtonPanel() {
            final JPanel buttons = newButtonRow();
            final JButton apply = new JButton("Apply");
            apply.setFocusable(false);
            apply.setToolTipText("Sets these parameters in the Detect Soma(s) prompt");
            apply.addActionListener(e -> commit());
            final JButton more = paletteOptions.getOptionsButton(pm -> {
                final JMenuItem clear = new JMenuItem("Clear ROIs", IconFactory.menuIcon(IconFactory.GLYPH.TRASH));
                clear.setToolTipText("Removes the ROIs drawn on the image (overlay)");
                clear.addActionListener(e -> clearUserRois());
                pm.add(clear);
                paletteOptions.addHelpItem(pm, helpHtml(), this);
                paletteOptions.addTourItem(pm, this::startTour);
            });
            more.setFocusable(false);
            roiButton = new JToggleButton("From ROIs");
            roiButton.setFocusable(false);
            roiButton.setToolTipText("<html>Sets min. radius and min. distance from ovals, rectangles or<br>"
                    + "lines (diameters) drawn on somata. While pressed, each ROI you draw is kept<br>"
                    + "(in the overlay): no need to hold Shift or press B. Parameters are set when released");
            roiButton.addActionListener(e -> setCapturing(roiButton.isSelected()));
            applyButton = apply;
            moreButton = more;
            buttons.add(roiButton);
            buttons.add(apply);
            buttons.add(more);
            return buttons;
        }

        // Sets the controls from the parameters
        private void load(final Params p) {
            loading = true;
            final boolean auto = p.threshold() < 0 || Double.isNaN(p.threshold());
            autoThreshold.setSelected(auto);
            thresholdSlider.setValue(toSlider((auto) ? (dataMin + dataMax) / 2 : p.threshold(), dataMin, dataMax));
            mipCheck.setSelected(volume == null || p.zSlice() < 0);
            radiusSlider.setValue(toSlider(p.minRadius(), 0, maxRadius));
            distanceSlider.setValue(toSlider(p.minDistance(), 0, maxDistance));
            loading = false;
            refreshLabels();
            updatePlaneLabel();
        }

        private int toSlider(final double v, final double lo, final double hi) {
            return (int) Math.round(Math.clamp((v - lo) / (hi - lo), 0d, 1d) * STEPS);
        }

        private double fromSlider(final JSlider s, final double lo, final double hi) {
            return lo + s.getValue() / (double) STEPS * (hi - lo);
        }

        private void refreshLabels() {
            final String unit = " " + snt.getSpacingUnits();
            thresholdSlider.setEnabled(!autoThreshold.isSelected());
            thresholdValue.setText((autoThreshold.isSelected()) ? "auto"
                    : SNTUtils.formatDouble(fromSlider(thresholdSlider, dataMin, dataMax), 2));
            radiusValue.setText(SNTUtils.formatDouble(fromSlider(radiusSlider, 0, maxRadius), 2) + unit);
            distanceValue.setText(SNTUtils.formatDouble(fromSlider(distanceSlider, 0, maxDistance), 2) + unit);
        }

        // Parameters currently set. EDT only
        Params current() {
            return new Params((autoThreshold.isSelected()) ? -1 : fromSlider(thresholdSlider, dataMin, dataMax),
                    fromSlider(radiusSlider, 0, maxRadius), fromSlider(distanceSlider, 0, maxDistance),
                    (previewZ() < 0) ? -1 : toFullZ(previewZ()));
        }

        // Slice of the preview detection runs on, or -1 for the max projection. EDT only
        int previewZ() {
            return (volume == null || mipCheck.isSelected()) ? -1 : paletteImage.getCurrentSlice() - 1;
        }

        boolean isMip() {
            return volume != null && mipCheck.isSelected();
        }

        private void updatePlaneLabel() {
            planeLabel = (volume == null) ? ""
                    : (mipCheck.isSelected()) ? "max projection" : "slice " + paletteImage.getCurrentSlice() + "/" + nZ;
        }

        // MIP checkbox: the projection is shown (opaque) over the stack, and detection runs on it
        private void planeChanged() {
            paletteOptions.showMip(mipCheck.isSelected(), 1d);
            updatePlaneLabel();
            setOvals(null);
            repaint(); // calls createSubtitle()
            requestDetection();
        }

        // Called when the image is updated: detection follows the displayed slice
        void sliceMayHaveChanged() {
            final int slice = paletteImage.getCurrentSlice();
            if (slice == lastSlice) return;
            lastSlice = slice;
            if (mipCheck.isSelected()) return;
            updatePlaneLabel();
            setOvals(null); // outdated
            debounce.restart();
            repaint(); // calls createSubtitle()
        }

        // Status while a detection is pending (generation: the request it refers to)
        void showBusy(final int generation) {
            if (generation == SomaPalette.this.generation.get()) statusLabel.setText("Detecting...");
        }

        // ROI types usable to measure somata: ovals and rectangles (bounding boxes), and straight lines (diameters)
        private boolean isAccepted(final Roi r) {
            if (r instanceof Line l) return Math.hypot(l.x2d - l.x1d, l.y2d - l.y1d) >= 2; // ignores stray clicks
            final java.awt.geom.Rectangle2D b = r.getFloatBounds();
            return (r.getType() == Roi.OVAL || r.getType() == Roi.RECTANGLE) && b.getWidth() >= 2
                    && b.getHeight() >= 2;
        }

        // {radius, x, y} of the soma marked by an accepted ROI, in calibrated units
        private double[] measure(final Roi r) {
            if (r instanceof Line l) {
                return new double[]{0.5 * Math.hypot((l.x2d - l.x1d) * spacing[0], (l.y2d - l.y1d) * spacing[1]),
                        0.5 * (l.x1d + l.x2d) * spacing[0], 0.5 * (l.y1d + l.y2d) * spacing[1]};
            }
            final java.awt.geom.Rectangle2D b = r.getFloatBounds();
            return new double[]{0.5 * Math.sqrt(b.getWidth() * spacing[0] * b.getHeight() * spacing[1]),
                    b.getCenterX() * spacing[0], b.getCenterY() * spacing[1]};
        }

        // While on, every ROI the user completes on the image is moved to the overlay and the parameters updated.
        // Turning it off sets the parameters from all the ROIs gathered
        private void setCapturing(final boolean on) {
            stopCapture();
            if (!on) {
                if (hasUserRois()) fromRois();
                return;
            }
            // RoiListener cannot be used: it reports MODIFIED on every mouse move while a ROI is being drawn, and
            // never reports completion of ovals
            captureListener = new MouseAdapter() {
                @Override
                public void mouseReleased(final MouseEvent e) {
                    SwingUtilities.invokeLater(() -> captureSelection());
                }
            };
            paletteImage.getCanvas().addMouseListener(captureListener);
            captureSelection(); // the current selection, if any
            showCaptureStatus();
        }

        // Detection is held until capture is turned off
        private void showCaptureStatus() {
            int n = 0;
            final Overlay ov = paletteImage.getOverlay();
            if (ov != null) {
                for (final Roi r : ov.toArray())
                    if (isUserRoi(r)) n++;
            }
            statusLabel.setForeground(infoColor);
            statusLabel.setText("Drawing ROIs: " + n + " so far. Toggle off From ROIs to set parameters");
        }

        void stopCapture() {
            if (captureListener != null && paletteImage.getCanvas() != null)
                paletteImage.getCanvas().removeMouseListener(captureListener);
            captureListener = null;
        }

        // ROIs gathered so far: any overlay item that is not a detection or the MIP (includes those added with B)
        private boolean isUserRoi(final Roi r) {
            return !SOMA_ROI.equals(r.getName()) && !PaletteOptions.MIP_OVERLAY_NAME.equals(r.getName());
        }

        private boolean hasUserRois() {
            final Overlay ov = paletteImage.getOverlay();
            if (ov == null) return false;
            for (final Roi r : ov.toArray())
                if (isUserRoi(r)) return true;
            return false;
        }

        private void captureSelection() {
            final Roi sel = paletteImage.getRoi();
            if (sel == null || !isAccepted(sel)) return;
            final Roi roi = (Roi) sel.clone();
            roi.setName(USER_ROI);
            roi.setStrokeColor(USER_ROI_COLOR);
            roi.setStrokeWidth(1.5);
            // Pin to the plane it was drawn on, unless on the (all depths) projection
            if (paletteImage.getNSlices() > 1 && !mipCheck.isSelected()) roi.setPosition(paletteImage.getCurrentSlice());
            Overlay ov = paletteImage.getOverlay();
            if (ov == null) ov = new Overlay();
            ov.add(roi);
            paletteImage.setOverlay(ov);
            paletteImage.deleteRoi();
            showCaptureStatus();
        }

        private void clearUserRois() {
            final Overlay old = paletteImage.getOverlay();
            if (old == null) return;
            final Overlay ov = new Overlay();
            for (final Roi r : old.toArray()) {
                if (!isUserRoi(r)) ov.add(r);
            }
            paletteImage.setOverlay(ov);
            statusLabel.setForeground(infoColor);
            statusLabel.setText(summary);
        }

        // Min. radius and distance from the ROIs the user has drawn (selection and/or overlay)
        private void fromRois() {
            final List<Roi> ovals = new ArrayList<>();
            final Roi sel = paletteImage.getRoi();
            if (sel != null) ovals.add(sel);
            final Overlay ov = paletteImage.getOverlay();
            if (ov != null) {
                for (final Roi r : ov.toArray()) {
                    if (isUserRoi(r)) ovals.add(r);
                }
            }
            ovals.removeIf(r -> !isAccepted(r));
            if (ovals.isEmpty()) {
                statusLabel.setForeground(GuiUtils.Colors.errorColor());
                statusLabel.setText("Draw ROIs (ovals, rectangles or lines) around somata first");
                return;
            }
            double minRadius = Double.MAX_VALUE;
            final double[][] centers = new double[ovals.size()][2];
            for (int i = 0; i < centers.length; i++) {
                final double[] m = measure(ovals.get(i));
                minRadius = Math.min(minRadius, m[0]);
                centers[i][0] = m[1];
                centers[i][1] = m[2];
            }
            // Margins: the smallest soma drawn, and the closest pair, must be retained
            final double radius = 0.75 * minRadius;
            double distance = 2 * radius;
            if (centers.length > 1) {
                double nn = Double.MAX_VALUE;
                for (int i = 0; i < centers.length; i++)
                    for (int j = i + 1; j < centers.length; j++)
                        nn = Math.min(nn, Math.hypot(centers[i][0] - centers[j][0], centers[i][1] - centers[j][1]));
                distance = 0.75 * nn;
            }
            loading = true;
            radiusSlider.setValue(toSlider(radius, 0, maxRadius));
            distanceSlider.setValue(toSlider(distance, 0, maxDistance));
            loading = false;
            refreshLabels();
            requestDetection();
        }

        // Replaces the detections shown, leaving other overlays (e.g., the MIP) as they are. Null for none
        private void setOvals(final List<SomaUtils.SomaResult> results) {
            final Overlay old = paletteImage.getOverlay();
            final Overlay ov = new Overlay();
            if (old != null) {
                for (final Roi r : old.toArray()) {
                    if (!SOMA_ROI.equals(r.getName())) ov.add(r);
                }
            }
            if (results != null) {
                for (final SomaUtils.SomaResult r : results) {
                    final Roi roi = r.toRoi(SomaUtils.OUTPUT_CIRCLE);
                    roi.setName(SOMA_ROI);
                    roi.setStrokeColor(OVAL_COLOR);
                    roi.setStroke(DETECTION_STROKE);
                    ov.add(roi);
                }
            }
            paletteImage.setOverlay(ov);
            if (paletteImage.getCanvas() != null) paletteImage.getCanvas().repaint();
        }

        void showResults(final List<SomaUtils.SomaResult> results, final Params p) {
            updatePlaneLabel();
            setOvals(results);
            final boolean auto = p.threshold() < 0;
            final String thr = (results.isEmpty()) ? ((auto) ? "auto" : SNTUtils.formatDouble(p.threshold(), 2))
                    : SNTUtils.formatDouble(results.getFirst().threshold(), 2) + ((auto) ? " (auto)" : "");
            summary = results.size() + " soma(s) | threshold " + thr;
            statusLabel.setForeground(infoColor);
            statusLabel.setText(summary);
            if (auto && autoThreshold.isSelected() && !results.isEmpty()) {
                loading = true;
                thresholdSlider.setValue(toSlider(results.getFirst().threshold(), dataMin, dataMax));
                loading = false;
            }
            repaint(); // calls createSubtitle()
        }

        // Large planes are zoomed out to fit the screen, others zoomed in to a usable size
        private void fitCanvasToScreen(final ImageCanvas ic, final ImagePlus imp) {
            final Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            final double maxSize = 0.55 * Math.min(screen.width, screen.height);
            final int size = Math.max(imp.getWidth(), imp.getHeight());
            if (size > maxSize) {
                final double mag = maxSize / size;
                ic.setMagnification(mag);
                ic.setSize((int) Math.ceil(imp.getWidth() * mag), (int) Math.ceil(imp.getHeight() * mag));
            } else {
                GuiUtils.zoomToScreenFraction(ic, 0.55, 8);
            }
        }

        @Override
        public String createSubtitle() {
            return "Soma Detection Preview" + ((planeNote != null) ? " (coarse)" : "")
                    + ((planeLabel.isEmpty()) ? "" : " | " + planeLabel) + " | " + summary.trim();
        }

        private void startTour() {
            runTour(this::registerCallouts);
        }

        private void registerCallouts(final String group) {
            int order = 0;
            final java.awt.Component canvas = getCanvas();
            if (canvas.isShowing() && canvas.getWidth() > 0) {
                CalloutManager.add(canvas, new Rectangle(canvas.getWidth() / 2, canvas.getHeight() / 2, 2, 2),
                        CalloutManager.AUTO, "<b>Preview</b><br>"
                                + "Detected somata are outlined in cyan. To measure a soma (or the distance between two),<br>" +
                                "draw an oval or line ROI on the preview and press <b>M</b>: radii and distances are reported<br>" +
                                "in calibrated units: use them to set <i>Min. radius</i> and <i>Min. distance</i>.", group, order++);
            }
            if (planePanel != null) {
                CalloutManager.add(planePanel, CalloutManager.AUTO, "<b>Plane</b><br>"
                        + "<i>MIP</i> shows (and detects on) the max projection of the volume. Otherwise, "
                        + "scroll the stack: detection follows the displayed slice.", group, order++);
            }
            CalloutManager.add(thresholdSlider, CalloutManager.AUTO, "<b>Threshold</b><br>"
                    + "Intensity threshold. <i>Auto</i> uses Otsu's method.", group, order++);
            CalloutManager.add(radiusSlider, CalloutManager.AUTO, "<b>Min. radius</b><br>"
                    + "Smaller detections are discarded as noise.", group, order++);
            CalloutManager.add(distanceSlider, CalloutManager.AUTO, "<b>Min. distance</b><br>"
                    + "Of two detections closer than this, only the strongest is kept.", group, order++);
            CalloutManager.add(statusLabel, CalloutManager.AUTO, "<b>Detections</b><br>"
                    + "Number of somata found in the preview, and the threshold used.<br>"
                    + "<i>Expected no. of somata</i> is set in the prompt, as it refers to the whole scope.",
                    group, order++);
            CalloutManager.add(roiButton, CalloutManager.AUTO, "<b>From ROIs</b><br>"
                    + "If the sliders are too slow or hard to tune (e.g., many candidate somata),<br>" +
                    "press this button and draw ovals, rectangles or lines (diameters)<br>" +
                    "across a few somata: <i>Min. radius</i><br>" +
                    "and <i>Min. distance</i> are set from their sizes and spacing.", group, order++);
            CalloutManager.add(applyButton, CalloutManager.AUTO, "<b>Apply</b><br>"
                    + "Sets these parameters in the <i>Detect Soma(s)</i> prompt.", group, order++);
            CalloutManager.add(moreButton, CalloutManager.AUTO, "<b>Actions/Options</b><br>"
                    + "Display options (LUT, B&amp;C), snapshot, help and this tour.", group, order);
        }

        private String helpHtml() {
            return "<HTML><div WIDTH=500><p>"
                    + "The <i>Soma Detection Wizard</i> previews multi-soma detection on a single plane: a slice, or a "
                    + "max projection of the volume. Detections are drawn as ovals and update as you adjust the "
                    + "parameters.</p><ul>"
                    + "<li><b>Plane</b>: <i>MIP</i> detects on (and shows) the max projection of the volume, i.e., "
                    + "somata at any depth. Otherwise, detection runs on the displayed slice: scroll the stack to "
                    + "browse it</li>"
                    + "<li><b>Threshold</b>: intensity threshold. <i>Auto</i> uses Otsu's method</li>"
                    + "<li><b>Min. radius</b>: smaller detections are discarded as noise (starts at "
                    + (int) DEFAULT_MIN_RADIUS_PX + " pixels if not set)</li>"
                    + "<li><b>Min. distance</b>: of two detections closer than this, only the strongest is kept</li>"
                    + "</ul><p>To measure somata, draw ROIs on the preview and press <b>M</b> (ImageJ's "
                    + "<i>Measure</i>): use the radii and distances to set <i>Min. radius</i> and <i>Min. distance</i>. "
                    + "Or press <i>From ROIs</i> and draw ovals, rectangles (bounding boxes) or straight lines "
                    + "(diameters) across a few somata: each is kept (solid magenta) and the parameters are set automatically (useful if the sliders are slow, e.g., for images "
                    + "with many candidate somata). Detections are dashed. <i>Clear ROIs</i> (in the options menu) "
                    + "discards them.</p>"
                    + "<p>For streamed data the preview is read from a coarse resolution level, which avoids "
                    + "loading the whole volume. Values are in calibrated units and also apply to the full "
                    + "resolution data. <i>Apply</i> sets the parameters in the <i>Detect Soma(s)</i> prompt. The expected "
                    + "number of somata is not previewed, as it refers to the whole scope: set it in the prompt.</p>";
        }
    }
}
