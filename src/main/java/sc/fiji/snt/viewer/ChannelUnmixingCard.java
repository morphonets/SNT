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

import bdv.tools.brightness.ConverterSetup;
import bdv.util.MipmapTransforms;
import bvv.vistools.BvvFunctions;
import bvv.vistools.BvvOptions;
import bvv.vistools.BvvStackSource;
import mpicbg.spim.data.generic.AbstractSpimData;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.view.Views;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.gui.ScriptInstaller;
import sc.fiji.snt.io.SpimDataUtils;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds and manages the "Channel Unmixing" card panel for real-time two-channel
 * subtraction in BVV. Extracted from {@link Bvv} for maintainability.
 * <p>
 * The card provides a slider controlling a subtraction weight {@code w} in
 * {@code result = signal - w * background} (clamped to [0, 65535]). On slider
 * release the mix is computed eagerly via {@link LoopBuilder} on a slab-cropped
 * sub-volume, then displayed as a new in-memory {@link BvvStackSource}; the
 * original channels are hidden. Cropping to the slab keeps the materialized
 * volume small so the computation is fast even on network storage.
 */
class ChannelUnmixingCard {

    private final Bvv owner;
    private final Set<String> cardTitles = new HashSet<>();
    private volatile SwingWorker<?, ?> imgProcessingWorker;

    ChannelUnmixingCard(final Bvv bvv) {
        this.owner = bvv;
    }

    private static void setError(final AbstractButton toggleButton,
                                 final JLabel statusLabel, final String msg) {
        final boolean wasSelected = toggleButton.isSelected();
        statusLabel.setText(msg);
        toggleButton.setSelected(false);
        if (wasSelected)
            GuiUtils.errorPrompt(msg + ".");
    }

    /**
     * Returns a unique card title for the given image name, avoiding duplicates
     * across multiple unmixing cards.
     */
    String uniqueTitle(final String imageName) {
        final String base = "Channel Unmixing: " + GuiUtils.truncate(imageName, 25);
        String title = base;
        int suffix = 2;
        while (!cardTitles.add(title)) {
            title = base + " (#" + suffix + ")";
            suffix++;
        }
        return title;
    }

    /**
     * Builds a "Channel Unmixing" card panel for two-channel subtraction.
     *
     * @param multi the multi-channel {@link BvvMultiSource} to mix
     * @return a JPanel suitable for {@code CardPanel.addCard()}
     */
    JPanel build(final BvvMultiSource multi) {
        return build(multi, null);
    }

    // Script generation

    /**
     * Builds a "Channel Unmixing" card panel for two-channel subtraction.
     *
     * @param multi    the multi-channel {@link BvvMultiSource} to mix
     * @param spimData the backing SpimData (may be {@code null} for in-memory sources)
     * @return a JPanel suitable for {@code CardPanel.addCard()}
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    JPanel build(final BvvMultiSource multi, final AbstractSpimData<?> spimData) {
        final JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        final GridBagConstraints gc = new GridBagConstraints();

        // Per-card mixed source (local to this card's closure, not shared across cards)
        final BvvStackSource<?>[] mixedSource = {null};
        // Slab bounds (world-space Z) at which the mixed source was computed.
        final double[] computedSlabZ = {Double.NaN, Double.NaN};
        // Display ranges (sigMin, sigMax, subMin, subMax) used for the last computation.
        final double[] computedDisplayRanges = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};

        final List<BvvStackSource<?>> sources = multi.getSources();
        final int nChannels = sources.size();
        final bdv.viewer.SourceAndConverter<?>[] channelSacs = new bdv.viewer.SourceAndConverter<?>[nChannels];
        final String[] channelNames = new String[nChannels];
        for (int i = 0; i < nChannels; i++) {
            channelSacs[i] = sources.get(i).getSources().getFirst();
            channelNames[i] = "Ch" + (i + 1);
        }
        final boolean hasPyramid = channelSacs[0].getSpimSource().getNumMipmapLevels() > 1;

        // UI components
        final JComboBox<String> signalCombo = new JComboBox<>(channelNames);
        signalCombo.setSelectedIndex(0);
        signalCombo.setToolTipText("<html>Signal channel (the channel to keep).<br>"
                + "Its display range (black/white levels) is used<br>"
                + "to normalise the subtraction.");
        final JComboBox<String> subtractCombo = new JComboBox<>(channelNames);
        subtractCombo.setSelectedIndex(Math.min(1, nChannels - 1));
        subtractCombo.setToolTipText("<html>Background channel to subtract.<br>"
                + "Its display range is used to scale the subtraction<br>"
                + "relative to the signal channel.");

        final JSlider weightSlider = new JSlider(0, 100, 0);
        weightSlider.setToolTipText(
                "<html>Subtraction weight <i>w</i>: the subtraction is normalised<br>"
                        + "by each channel's display range (brightness/contrast levels).<br>"
                        + "<b>Workflow:</b> First adjust the B&amp;C sliders so that an<br>"
                        + "autofluorescent feature looks equally bright in both channels,<br>"
                        + "then increase <i>w</i> until the bleed-through disappears.<br>"
                        + "Higher values remove more background but may clip signal.<br>"
                        + "Computed on slider release.");
        final JLabel weightLabel = new JLabel("w = 0.00");
        weightSlider.addChangeListener(e ->
                weightLabel.setText(String.format("w = %.2f", weightSlider.getValue() / 100.0)));

        final JToggleButton enableToggle = new JToggleButton("Enable");
        enableToggle.setToolTipText("<html>Enable display-normalised channel subtraction.<br>"
                + "The subtraction uses each channel's current brightness<br>"
                + "levels, so adjust B&amp;C first to calibrate the unmixing.<br>"
                + "Large volumes may require an active thin Slab View.");

        final JLabel statusLabel = new JLabel(" ");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC, statusLabel.getFont().getSize2D() - 1));

        final JButton resetButton = GuiUtils.Buttons.undo(null);
        resetButton.setToolTipText("Reset: remove mixed source and restore original channels");

        // Slab constraints
        final double[] cal = owner.getCal();
        final double zCal = (cal != null && cal.length > 2 && cal[2] > 0) ? cal[2] : 1.0;
        final double maxSlabThickness = zCal * 10;
        final Bvv.PathRenderingOptions renderingOptions = owner.getRenderingOptions();

        final java.util.function.BooleanSupplier slabRequired = () -> spimData != null || hasPyramid;

        // Debounce timer
        final int RECOMPUTE_DELAY_MS = 500;
        final javax.swing.Timer[] recomputeTimer = {null};
        final boolean[] computing = {false};

        // Slab/state checking
        final Runnable checkSlabAndUpdateUI = () -> {
            if (computing[0]) return;
            final boolean sameChannel = signalCombo.getSelectedIndex() == subtractCombo.getSelectedIndex();
            final double zMin = renderingOptions.getSlabZMin();
            final double zMax = renderingOptions.getSlabZMax();
            final boolean slabActive = zMin != Double.NEGATIVE_INFINITY;
            final boolean slabThin = slabActive && (zMax - zMin) <= maxSlabThickness;
            final boolean needSlab = slabRequired.getAsBoolean();

            boolean recomputePending = false;
            if (mixedSource[0] != null && !Double.isNaN(computedSlabZ[0])) {
                final boolean slabMoved = zMin < computedSlabZ[0] - zCal
                        || zMax > computedSlabZ[1] + zCal;
                if (!slabActive || (needSlab && !slabThin)) {
                    if (recomputeTimer[0] != null) recomputeTimer[0].stop();
                    mixedSource[0].removeFromBdv();
                    mixedSource[0] = null;
                    computedSlabZ[0] = Double.NaN;
                    computedSlabZ[1] = Double.NaN;
                    Arrays.fill(computedDisplayRanges, Double.NaN);
                    multi.setActive(true);
                    owner.repaint();
                } else if (slabMoved && enableToggle.isSelected() && weightSlider.getValue() > 0) {
                    recomputePending = true;
                    if (recomputeTimer[0] != null) recomputeTimer[0].restart();
                }
            }

            if (sameChannel) {
                weightSlider.setEnabled(false);
                setError(enableToggle, statusLabel, "Signal and background channels must differ");
            } else if (needSlab && !slabActive) {
                weightSlider.setEnabled(false);
                setError(enableToggle, statusLabel, "Slab view required to limit memory usage. Please enable it.");
            } else if (needSlab && !slabThin) {
                weightSlider.setEnabled(false);
                setError(enableToggle, statusLabel, "Slab too thick for memory safety (≤" + (int) (maxSlabThickness / zCal) + " slices needed)");
            } else if (enableToggle.isSelected()) {
                weightSlider.setEnabled(true);
                if (recomputePending) {
                    statusLabel.setText("Slab moved: Recomputing...");
                } else if (mixedSource[0] != null) {
                    final int sIdx = signalCombo.getSelectedIndex();
                    final int bIdx = subtractCombo.getSelectedIndex();
                    final ConverterSetup csS = sources.get(sIdx).getConverterSetups().getFirst();
                    final ConverterSetup csB = sources.get(bIdx).getConverterSetups().getFirst();
                    final boolean rangesChanged =
                            csS.getDisplayRangeMin() != computedDisplayRanges[0]
                                    || csS.getDisplayRangeMax() != computedDisplayRanges[1]
                                    || csB.getDisplayRangeMin() != computedDisplayRanges[2]
                                    || csB.getDisplayRangeMax() != computedDisplayRanges[3];
                    if (rangesChanged)
                        statusLabel.setText("B&C levels changed: Adjust weight to recompute");
                    else
                        statusLabel.setText(String.format("Showing unmixed (w = %.2f)", weightSlider.getValue() / 100.0));
                } else {
                    statusLabel.setText("Release slider to compute unmixing");
                }
            } else {
                weightSlider.setEnabled(false);
                statusLabel.setText(" ");
            }
        };

        // Re-check when channel selection changes
        signalCombo.addActionListener(e -> checkSlabAndUpdateUI.run());
        subtractCombo.addActionListener(e -> checkSlabAndUpdateUI.run());

        // Re-check when B&C display ranges change
        try {
            for (final BvvStackSource<?> src : sources) {
                src.getConverterSetups().getFirst().setupChangeListeners().add(s ->
                        SwingUtilities.invokeLater(checkSlabAndUpdateUI));
            }
        } catch (final Exception ignored) {
        }

        // Computation
        final Runnable computeMix = () -> {
            if (computing[0]) return;
            final double w = weightSlider.getValue() / 100.0;
            if (w <= 0) {
                if (mixedSource[0] != null) {
                    mixedSource[0].removeFromBdv();
                    mixedSource[0] = null;
                    computedSlabZ[0] = Double.NaN;
                    computedSlabZ[1] = Double.NaN;
                    Arrays.fill(computedDisplayRanges, Double.NaN);
                }
                multi.setActive(true);
                owner.repaint();
                statusLabel.setText("No subtraction (w=0)");
                return;
            }
            final int sigIdx = signalCombo.getSelectedIndex();
            final int subIdx = subtractCombo.getSelectedIndex();
            if (sigIdx == subIdx) {
                statusLabel.setText("Signal and subtract channels must differ");
                return;
            }
            final String sigName = channelNames[sigIdx];
            final String subName = channelNames[subIdx];

            final bdv.viewer.Source<?> sigSource = channelSacs[sigIdx].getSpimSource();
            final AffineTransform3D screenTransform = new AffineTransform3D();
            owner.getViewer().getViewer().state().getViewerTransform(screenTransform);
            final int bestLevel = MipmapTransforms.getBestMipMapLevel(screenTransform, sigSource, 0);

            @SuppressWarnings("unchecked") final RandomAccessibleInterval<RealType<?>> sigTyped =
                    (RandomAccessibleInterval<RealType<?>>) sigSource.getSource(0, bestLevel);
            @SuppressWarnings("unchecked") final RandomAccessibleInterval<RealType<?>> subTyped =
                    (RandomAccessibleInterval<RealType<?>>) channelSacs[subIdx].getSpimSource().getSource(0, bestLevel);

            final AffineTransform3D sigSrcToWorld = new AffineTransform3D();
            sigSource.getSourceTransform(0, bestLevel, sigSrcToWorld);
            final double mipZCal = Math.sqrt(
                    sigSrcToWorld.get(0, 2) * sigSrcToWorld.get(0, 2) +
                            sigSrcToWorld.get(1, 2) * sigSrcToWorld.get(1, 2) +
                            sigSrcToWorld.get(2, 2) * sigSrcToWorld.get(2, 2));
            final double effZCal = mipZCal > 0 ? mipZCal : zCal;

            final double slabZMin = renderingOptions.getSlabZMin();
            final double slabZMax = renderingOptions.getSlabZMax();
            final RandomAccessibleInterval<RealType<?>> sigCropped;
            final RandomAccessibleInterval<RealType<?>> subCropped;
            final double[] calOffset;
            if (slabZMin != Double.NEGATIVE_INFINITY && sigTyped.numDimensions() >= 3) {
                final long zMinPx = Math.max(sigTyped.min(2), (long) Math.floor(slabZMin / effZCal));
                final long zMaxPx = Math.min(sigTyped.max(2), (long) Math.ceil(slabZMax / effZCal));
                sigCropped = Views.interval(sigTyped,
                        new long[]{sigTyped.min(0), sigTyped.min(1), zMinPx},
                        new long[]{sigTyped.max(0), sigTyped.max(1), zMaxPx});
                subCropped = Views.interval(subTyped,
                        new long[]{subTyped.min(0), subTyped.min(1), zMinPx},
                        new long[]{subTyped.max(0), subTyped.max(1), zMaxPx});
                calOffset = new double[]{
                        sigTyped.min(0) * sigSrcToWorld.get(0, 0),
                        sigTyped.min(1) * sigSrcToWorld.get(1, 1),
                        zMinPx * mipZCal};
            } else {
                sigCropped = sigTyped;
                subCropped = subTyped;
                calOffset = null;
            }

            final ConverterSetup csSig = sources.get(sigIdx).getConverterSetups().getFirst();
            final ConverterSetup csSub = sources.get(subIdx).getConverterSetups().getFirst();
            final double sigMin = csSig.getDisplayRangeMin();
            final double sigMax = csSig.getDisplayRangeMax();
            final double subMin = csSub.getDisplayRangeMin();
            final double subMax = csSub.getDisplayRangeMax();
            final double sigRange = sigMax - sigMin;
            final double subRange = subMax - subMin;
            final double rangeScale = (subRange > 0) ? sigRange / subRange : 1.0;

            // Memory guard for pyramid sources
            if (spimData != null || hasPyramid) {
                final long[] cropDims = {
                        sigCropped.max(0) - sigCropped.min(0) + 1,
                        sigCropped.max(1) - sigCropped.min(1) + 1,
                        sigCropped.max(2) - sigCropped.min(2) + 1};
                final long nPixels = cropDims[0] * cropDims[1] * cropDims[2];
                final long estimatedBytes = nPixels * 2L * 3L;
                final Runtime rt = Runtime.getRuntime();
                final long freeHeap = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
                if (estimatedBytes > freeHeap / 2) {
                    statusLabel.setText(String.format("Slab too large at mip level %d (%d MB needed, %d MB free). "
                                    + "Reduce slab thickness or zoom in to save memory.",
                            bestLevel, estimatedBytes / (1024 * 1024), freeHeap / (1024 * 1024)));
                    return;
                }
            }

            computing[0] = true;
            weightSlider.setEnabled(false);
            statusLabel.setText(String.format("Computing (level %d)...", bestLevel));
            owner.updateStatus("Computing channel unmixing...", 0, -1);
            panel.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            cancelWorker();

            final SwingWorker<Void, Void> worker = new SwingWorker<>() {
                @Override
                protected Void doInBackground() {
                    final RandomAccessibleInterval<RealType<?>> sigZM = Views.zeroMin(sigCropped);
                    final RandomAccessibleInterval<RealType<?>> subZM = Views.zeroMin(subCropped);
                    final long[] cropSize = sigZM.dimensionsAsLongArray();

                    final ArrayImg<UnsignedShortType, ?> sigMat;
                    final ArrayImg<UnsignedShortType, ?> subMat;
                    final ArrayImg<UnsignedShortType, ?> mixed;
                    try {
                        sigMat = ArrayImgs.unsignedShorts(cropSize[0], cropSize[1], cropSize[2]);
                        LoopBuilder.setImages(sigZM, sigMat)
                                .forEachPixel((a, out) -> out.setReal(a.getRealDouble()));
                        if (isCancelled()) return null;

                        subMat = ArrayImgs.unsignedShorts(cropSize[0], cropSize[1], cropSize[2]);
                        LoopBuilder.setImages(subZM, subMat)
                                .forEachPixel((a, out) -> out.setReal(a.getRealDouble()));
                        if (isCancelled()) return null;

                        mixed = ArrayImgs.unsignedShorts(cropSize[0], cropSize[1], cropSize[2]);
                        LoopBuilder.setImages(sigMat, subMat, mixed)
                                .multiThreaded()
                                .forEachPixel((a, b, out) -> {
                                    final double aNorm = a.getRealDouble() - sigMin;
                                    final double bNorm = (b.getRealDouble() - subMin) * rangeScale;
                                    final double val = aNorm - w * bNorm + sigMin;
                                    out.setReal(Math.max(0, Math.min(BvvUtils.MAX_UINT16, val)));
                                });
                    } catch (final OutOfMemoryError oom) {
                        SwingUtilities.invokeLater(() -> {
                            panel.setCursor(Cursor.getDefaultCursor());
                            computing[0] = false;
                            owner.updateStatus("", 0, 0);
                            statusLabel.setText("Out of memory. Enable Slab View to reduce memory usage.");
                            weightSlider.setEnabled(false);
                            enableToggle.setSelected(false);
                            checkSlabAndUpdateUI.run();
                        });
                        return null;
                    }

                    if (isCancelled()) return null;

                    SwingUtilities.invokeLater(() -> {
                        if (isCancelled()) {
                            panel.setCursor(Cursor.getDefaultCursor());
                            computing[0] = false;
                            owner.updateStatus("", 0, 0);
                            statusLabel.setText("Cancelled");
                            return;
                        }
                        if (mixedSource[0] != null) mixedSource[0].removeFromBdv();
                        final BvvOptions addOpt = new BvvOptions().addTo(owner.getBvvHandle());
                        final String srcName = String.format("%s − %.2f × %s", sigName, w, subName);
                        final String unit = owner.getCalUnit() != null ? owner.getCalUnit() : "pixel";
                        final AffineTransform3D srcT = new AffineTransform3D();
                        srcT.set(sigSrcToWorld);
                        if (calOffset != null) {
                            srcT.set(calOffset[0], 0, 3);
                            srcT.set(calOffset[1], 1, 3);
                            srcT.set(calOffset[2], 2, 3);
                        }
                        final double[] mipCal = new double[]{
                                sigSrcToWorld.get(0, 0),
                                sigSrcToWorld.get(1, 1),
                                mipZCal};
                        final SpimDataUtils.CalibratedSource<UnsignedShortType> src =
                                new SpimDataUtils.CalibratedSource<>(mixed,
                                        new UnsignedShortType(),
                                        srcT, srcName, mipCal, unit);
                        mixedSource[0] = BvvFunctions.show((bdv.viewer.Source) src, 1, addOpt);
                        multi.setActive(false);
                        final BvvStackSource<?> ms = mixedSource[0];
                        final javax.swing.Timer applyRange = new javax.swing.Timer(100, ev -> {
                            ms.setDisplayRange(sigMin, sigMax);
                            ms.setColor(new ARGBType(0x0000FFFF));
                            owner.repaint();
                        });
                        applyRange.setRepeats(false);
                        applyRange.start();
                        if (slabZMin == Double.NEGATIVE_INFINITY) {
                            computedSlabZ[0] = Double.NaN;
                            computedSlabZ[1] = Double.NaN;
                        } else {
                            computedSlabZ[0] = slabZMin;
                            computedSlabZ[1] = slabZMax;
                        }
                        computedDisplayRanges[0] = sigMin;
                        computedDisplayRanges[1] = sigMax;
                        computedDisplayRanges[2] = subMin;
                        computedDisplayRanges[3] = subMax;
                        statusLabel.setText(String.format("Showing %s − %.2f × %s (level %d)",
                                sigName, w, subName, bestLevel));
                        if (hasPyramid && owner.getViewer() != null) {
                            owner.getViewer().getViewer().showMessage(
                                    "Unmixed result is single-resolution (level " + bestLevel + ")");
                        }
                        panel.setCursor(Cursor.getDefaultCursor());
                        computing[0] = false;
                        owner.updateStatus("", 0, 0);
                        checkSlabAndUpdateUI.run();
                    });
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                    } catch (final java.util.concurrent.CancellationException ignored) {
                        SwingUtilities.invokeLater(() -> {
                            panel.setCursor(Cursor.getDefaultCursor());
                            computing[0] = false;
                            owner.updateStatus("", 0, 0);
                            statusLabel.setText("Cancelled");
                            checkSlabAndUpdateUI.run();
                        });
                    } catch (final Exception ex) {
                        SwingUtilities.invokeLater(() -> {
                            panel.setCursor(Cursor.getDefaultCursor());
                            computing[0] = false;
                            owner.updateStatus("", 0, 0);
                            statusLabel.setText("Error: " + ex.getMessage());
                            checkSlabAndUpdateUI.run();
                        });
                    }
                }
            };
            imgProcessingWorker = worker;
            worker.execute();
        };

        // Initialize debounce timer
        recomputeTimer[0] = new javax.swing.Timer(RECOMPUTE_DELAY_MS, e -> {
            if (enableToggle.isSelected() && weightSlider.getValue() > 0 && !computing[0]) {
                computeMix.run();
            }
        });
        recomputeTimer[0].setRepeats(false);

        weightSlider.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseReleased(final java.awt.event.MouseEvent e) {
                if (enableToggle.isSelected() && weightSlider.isEnabled()) {
                    computeMix.run();
                }
            }
        });

        enableToggle.addActionListener(e -> {
            checkSlabAndUpdateUI.run();
            if (!enableToggle.isSelected()) {
                cancelWorker();
                recomputeTimer[0].stop();
                if (mixedSource[0] != null) {
                    mixedSource[0].removeFromBdv();
                    mixedSource[0] = null;
                    computedSlabZ[0] = Double.NaN;
                    computedSlabZ[1] = Double.NaN;
                    Arrays.fill(computedDisplayRanges, Double.NaN);
                }
                multi.setActive(true);
                owner.repaint();
                statusLabel.setText(" ");
            } else if (weightSlider.isEnabled() && weightSlider.getValue() > 0) {
                computeMix.run();
            }
        });

        resetButton.addActionListener(e -> {
            cancelWorker();
            recomputeTimer[0].stop();
            weightSlider.setValue(0);
            enableToggle.setSelected(false);
            if (mixedSource[0] != null) {
                mixedSource[0].removeFromBdv();
                mixedSource[0] = null;
                computedSlabZ[0] = Double.NaN;
                computedSlabZ[1] = Double.NaN;
                Arrays.fill(computedDisplayRanges, Double.NaN);
            }
            multi.setActive(true);
            owner.repaint();
            statusLabel.setText(" ");
        });

        // Periodic slab check
        final javax.swing.Timer slabCheckTimer = new javax.swing.Timer(500, e -> {
            if (panel.isShowing() && (enableToggle.isSelected() || mixedSource[0] != null)) {
                checkSlabAndUpdateUI.run();
            }
        });
        slabCheckTimer.setRepeats(true);
        slabCheckTimer.start();

        // Layout
        // Row 0: Signal: [combo]  Background: [combo]
        gc.gridy = 0;
        gc.gridx = 0;
        gc.weightx = 0;
        gc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Signal:"), gc);
        gc.gridx++;
        gc.weightx = 0.5;
        gc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(signalCombo, gc);
        gc.gridx++;
        gc.weightx = 0;
        gc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("  Background:"), gc);
        gc.gridx = 3;
        gc.weightx = 0.5;
        gc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(subtractCombo, gc);
        // Row 1: [Enable] [slider] weight [Reset]
        gc.gridy = 1;
        gc.gridx = 0;
        gc.weightx = 0;
        gc.fill = GridBagConstraints.NONE;
        panel.add(enableToggle, gc);
        gc.gridx = 1;
        gc.gridwidth = 2;
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(weightSlider, gc);
        gc.gridx = 3;
        gc.gridwidth = 1;
        gc.weightx = 0;
        gc.fill = GridBagConstraints.NONE;
        panel.add(weightLabel, gc);
        gc.gridx = 4;
        panel.add(resetButton, gc);
        // Row 2: [status] [Script]
        gc.gridy++;
        gc.gridx = 0;
        gc.gridwidth = spimData != null ? 4 : 5;
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(statusLabel, gc);
        if (spimData != null) {
            final JButton scriptButton = GuiUtils.Buttons.toolbarButton(
                    IconFactory.GLYPH.CODE, null, 1f);
            scriptButton.setToolTipText("<html>Generate a Groovy script for full-volume unmixing.<br>"
                    + "Uses the current signal/background channels and weight.<br>"
                    + "Run it offline (e.g., in Fiji's Script Editor) on the full dataset.");
            scriptButton.addActionListener(e -> {
                final double w = weightSlider.getValue() / 100.0;
                if (w <= 0) {
                    GuiUtils.errorPrompt("Set a non-zero weight first.");
                    return;
                }
                final int sigIdx = signalCombo.getSelectedIndex();
                final int subIdx = subtractCombo.getSelectedIndex();
                if (sigIdx == subIdx) {
                    GuiUtils.errorPrompt("Signal and background channels must differ.");
                    return;
                }
                final String script = generateUnmixingScript(spimData, sigIdx, subIdx, w,
                        channelNames[sigIdx], channelNames[subIdx]);
                ScriptInstaller.newScript(script,
                        String.format("Unmix_%s_minus_%s.groovy", channelNames[sigIdx], channelNames[subIdx]));
            });
            gc.gridx = 4;
            gc.gridwidth = 1;
            gc.weightx = 0;
            gc.fill = GridBagConstraints.NONE;
            panel.add(scriptButton, gc);
        }
        weightSlider.setEnabled(false);
        return panel;
    }

    // Helpers

    /**
     * Generates a Groovy script for full-volume channel unmixing by loading
     * the {@code ChannelUnmixing.groovy} template and replacing placeholders.
     */
    private String generateUnmixingScript(final AbstractSpimData<?> spimData,
                                          final int sigIdx, final int subIdx,
                                          final double weight,
                                          final String sigName, final String subName) {
        String filePath = owner.getSpimDataFilePath(spimData);

        final int nLevels;
        try {
            final var setups = spimData.getSequenceDescription().getViewSetupsOrdered();
            if (sigIdx < setups.size()) {
                final var imgLoader = spimData.getSequenceDescription().getImgLoader();
                if (imgLoader instanceof bdv.ViewerImgLoader) {
                    nLevels = ((bdv.ViewerImgLoader) imgLoader)
                            .getSetupImgLoader(setups.get(sigIdx).getId()).numMipmapLevels();
                } else {
                    nLevels = 1;
                }
            } else {
                nLevels = 1;
            }
        } catch (final Exception e) {
            return "// Error resolving dataset metadata: " + e.getMessage();
        }

        final var setups = spimData.getSequenceDescription().getViewSetupsOrdered();
        final int sigSetupId = setups.get(sigIdx).getId();
        final int subSetupId = setups.get(subIdx).getId();
        final int nTimepoints = spimData.getSequenceDescription().getTimePoints().size();

        final String template = BvvUtils.loadBoilerPlateScript("ChannelUnmixing.groovy");
        return template
                .replace("#{INPUT_PATH}", filePath.replace("\\", "\\\\").replace("'", "\\'"))
                .replace("#{SIG_SETUP}", String.valueOf(sigSetupId))
                .replace("#{SUB_SETUP}", String.valueOf(subSetupId))
                .replace("#{WEIGHT}", String.format("%.4f", weight))
                .replace("#{N_LEVELS}", String.valueOf(nLevels))
                .replace("#{N_TIMEPOINTS}", String.valueOf(nTimepoints))
                .replace("#{SIG_NAME}", sigName)
                .replace("#{SUB_NAME}", subName);
    }

    private void cancelWorker() {
        if (imgProcessingWorker != null && !imgProcessingWorker.isDone()) {
            imgProcessingWorker.cancel(true);
        }
    }
}
