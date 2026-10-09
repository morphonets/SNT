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

import mpicbg.spim.data.generic.AbstractSpimData;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.gui.ScriptInstaller;
import sc.fiji.snt.io.SpimDataUtils;

import javax.swing.*;
import java.awt.*;
import java.util.HashSet;
import java.util.Set;

/**
 * Builds and manages the "Channel Unmixing" card panel for real-time two-channel
 * subtraction in BDV/BVV. The card provides a slider controlling a subtraction
 * weight {@code w} in {@code result = signal - w * background} (clamped to
 * [0, 65535]), normalised by the display range of each channel. It only deals
 * with the UI: how the mix is computed and displayed is up to the viewer-specific
 * {@link UnmixEngine}.
 */
class ChannelUnmixingCard {

    private final AbstractBigViewer owner;
    private final Set<String> cardTitles = new HashSet<>();

    ChannelUnmixingCard(final AbstractBigViewer viewer) {
        this.owner = viewer;
    }

    /**
     * Flags an invalid state: shows the (short) message in the status label, with the full message as its tooltip,
     * and untoggles the Enable button. If the button was toggled on, the full message is displayed either in a
     * modal dialog ({@code prompt}: for errors the user just triggered, e.g., by pressing Enable) or in the viewer's
     * overlay. The latter is non-blocking, which matters for state changes that are not the user's direct doing
     * (e.g., zooming): a modal dialog opened while the EDT is flooded with viewer refreshes may stay blank for a while
     */
    private void setError(final AbstractButton toggleButton, final JLabel statusLabel, final boolean prompt,
                          final String msg) {
        final boolean wasSelected = toggleButton.isSelected();
        final int index = msg.indexOf('(');
        statusLabel.setText(msg.substring(0, (index > -1) ? index : msg.length()));
        statusLabel.setToolTipText(msg);
        toggleButton.setSelected(false);
        if (!wasSelected) return;
        if (prompt)
            GuiUtils.errorPrompt(msg);
        else
            owner.showViewerMessage(msg);
    }

    /**
     * Returns a unique card title for the given image name, avoiding duplicates
     * across multiple unmixing cards.
     */
    String uniqueTitle(final String imageName) {
        final String base = "Channel Unmixing: " + GuiUtils.Text.truncate(imageName, 25);
        String title = base;
        int suffix = 2;
        while (!cardTitles.add(title)) {
            title = base + " (#" + suffix + ")";
            suffix++;
        }
        return title;
    }

    /** The name of the i-th channel's source without its " (ChN)" suffix. Null if unavailable */
    private static String sourceName(final ChannelGroup group, final int i) {
        try {
            final String name = group.channelSource(i).getSpimSource().getName();
            return (name == null || name.isBlank()) ? null : name.replaceAll("\\s*\\(Ch\\d+\\)$", "");
        } catch (final Exception ignored) {
            return null;
        }
    }

    /** Shows the source name of each channel as a tooltip of the combo items */
    private static void installNameTooltips(final JComboBox<String> combo, final String[] names) {
        final javax.swing.ListCellRenderer<? super String> base = combo.getRenderer();
        combo.setRenderer((list, value, index, selected, focus) -> {
            final java.awt.Component c = base.getListCellRendererComponent(list, value, index, selected, focus);
            if (c instanceof JComponent jc && index >= 0 && index < names.length)
                jc.setToolTipText(names[index]);
            return c;
        });
    }

    /**
     * Builds a "Channel Unmixing" card panel for two-channel subtraction.
     *
     * @param group    the channels to mix
     * @param dataset  the backing dataset: an {@link AbstractSpimData}, a {@link SpimDataUtils.N5Sources}, or
     *                 {@code null} for in-memory sources
     * @param engine   the viewer-specific back end computing and displaying the mix
     * @return a JPanel suitable for {@code CardPanel.addCard()}
     */
    JPanel build(final ChannelGroup group, final Object dataset, final UnmixEngine engine) {
        final JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        final GridBagConstraints gc = new GridBagConstraints();

        final int nChannels = group.size();
        final String[] channelNames = new String[nChannels];
        final String[] sourceNames = new String[nChannels]; // full names: tooltips only, the combos stay narrow
        for (int i = 0; i < nChannels; i++) {
            channelNames[i] = "Ch" + (i + 1);
            sourceNames[i] = sourceName(group, i);
        }

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

        installNameTooltips(signalCombo, sourceNames);
        installNameTooltips(subtractCombo, sourceNames);

        final JSlider weightSlider = new JSlider(0, 100, 0);
        weightSlider.setToolTipText(
                "<html>Subtraction weight <i>w</i>: the subtraction is normalised<br>"
                        + "by each channel's display range (brightness/contrast levels).<br>"
                        + "<b>Workflow:</b> First adjust the B&amp;C sliders so that an<br>"
                        + "autofluorescent feature looks equally bright in both channels,<br>"
                        + "then increase <i>w</i> until the bleed-through disappears.<br>"
                        + "Higher values remove more background but may clip signal.<br>"
                        + engine.sliderHint());
        final JLabel weightLabel = new JLabel("w = 0.00");
        weightSlider.addChangeListener(e ->
                weightLabel.setText(String.format("w = %.2f", weightSlider.getValue() / 100.0)));

        final JToggleButton enableToggle = new JToggleButton("Enable");
        enableToggle.setToolTipText("<html>Enable display-normalised channel subtraction.<br>"
                + "The subtraction uses each channel's current brightness<br>"
                + "levels, so adjust B&amp;C first to calibrate the unmixing.<br>"
                + engine.enableHint());

        final JLabel statusLabel = new JLabel(" ");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC, statusLabel.getFont().getSize2D() - 1));

        final JButton resetButton = GuiUtils.Buttons.undo("Reset: remove mixed source and restore original channels");

        // True only while the check triggered by pressing Enable runs (see setError)
        final boolean[] userInitiated = {false};

        // State checking
        final Runnable check = () -> {
            if (engine.isBusy()) return;
            statusLabel.setToolTipText(null);
            final int sigIdx = signalCombo.getSelectedIndex();
            final int subIdx = subtractCombo.getSelectedIndex();
            final double w = weightSlider.getValue() / 100.0;
            final String engineError = engine.validate(sigIdx, subIdx, enableToggle.isSelected() && w > 0);

            if (sigIdx == subIdx) {
                weightSlider.setEnabled(false);
                setError(enableToggle, statusLabel, userInitiated[0], "Signal and background channels must differ.");
            } else if (engineError != null) {
                weightSlider.setEnabled(false);
                setError(enableToggle, statusLabel, userInitiated[0], engineError);
            } else if (enableToggle.isSelected()) {
                weightSlider.setEnabled(true);
                statusLabel.setText(engine.status(sigIdx, subIdx, w));
            } else {
                weightSlider.setEnabled(false);
                statusLabel.setText(" ");
            }
        };

        // Mix computation/update
        final Runnable applyMix = () -> {
            if (engine.isBusy()) return;
            final double w = weightSlider.getValue() / 100.0;
            // Live engines keep showing the (signal-only) mix at w=0, so that the view does not jump to the
            // original channels as the slider crosses 0
            if (w <= 0 && !engine.isLive()) {
                engine.clear();
                statusLabel.setText("No subtraction (w=0)");
                return;
            }
            final int sigIdx = signalCombo.getSelectedIndex();
            final int subIdx = subtractCombo.getSelectedIndex();
            if (sigIdx == subIdx) {
                statusLabel.setText("Signal and subtract channels must differ");
                return;
            }
            engine.apply(sigIdx, subIdx, w);
        };

        engine.attach(new UnmixEngine.Host() {
            @Override
            public int signalIndex() {
                return signalCombo.getSelectedIndex();
            }

            @Override
            public int backgroundIndex() {
                return subtractCombo.getSelectedIndex();
            }

            @Override
            public double weight() {
                return weightSlider.getValue() / 100.0;
            }

            @Override
            public String channelName(final int i) {
                return channelNames[i];
            }

            @Override
            public boolean isActive() {
                return enableToggle.isSelected() && weightSlider.getValue() > 0;
            }

            @Override
            public void setStatus(final String msg) {
                statusLabel.setText(msg);
            }

            @Override
            public void setBusy(final boolean busy) {
                if (busy) weightSlider.setEnabled(false);
                panel.setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
            }

            @Override
            public void requestCheck() {
                check.run();
            }

            @Override
            public void deselectEnable() {
                enableToggle.setSelected(false);
            }
        });

        // Re-check when channel selection changes
        signalCombo.addActionListener(e -> check.run());
        subtractCombo.addActionListener(e -> check.run());

        // Re-check when B&C display ranges change
        try {
            for (int i = 0; i < nChannels; i++) {
                group.channelSetup(i).setupChangeListeners().add(s ->
                        SwingUtilities.invokeLater(check));
            }
        } catch (final Exception ignored) {
        }

        if (engine.isLive()) {
            weightSlider.addChangeListener(e -> {
                if (enableToggle.isSelected() && weightSlider.isEnabled()) applyMix.run();
            });
        } else {
            weightSlider.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseReleased(final java.awt.event.MouseEvent e) {
                    if (enableToggle.isSelected() && weightSlider.isEnabled()) applyMix.run();
                }
            });
        }

        enableToggle.addActionListener(e -> {
            userInitiated[0] = true;
            try {
                check.run();
            } finally {
                userInitiated[0] = false;
            }
            if (!enableToggle.isSelected()) {
                engine.clear();
                statusLabel.setText(" ");
            } else if (weightSlider.isEnabled() && weightSlider.getValue() > 0) {
                applyMix.run();
            }
        });

        resetButton.addActionListener(e -> {
            enableToggle.setSelected(false);
            weightSlider.setValue(0);
            engine.clear();
            statusLabel.setText(" ");
        });

        // Periodic check
        final javax.swing.Timer checkTimer = new javax.swing.Timer(500, e -> {
            if (panel.isShowing() && (enableToggle.isSelected() || engine.hasResult())) check.run();
        });
        checkTimer.setRepeats(true);
        checkTimer.start();

        // Layout
        // Row 0: Signal: [combo]  Background: [combo]
        // Nested panel so that both combos get identical widths when resizing, whatever the column layout below
        final JPanel combos = new JPanel(new GridBagLayout());
        combos.setOpaque(false);
        final GridBagConstraints cc = new GridBagConstraints();
        cc.gridy = 0;
        cc.anchor = GridBagConstraints.WEST;
        cc.insets = gc.insets;
        cc.gridx = 0;
        combos.add(new JLabel("Signal: "), cc);
        cc.gridx = 1;
        cc.weightx = 1.0;
        cc.fill = GridBagConstraints.HORIZONTAL;
        combos.add(signalCombo, cc);
        cc.gridx = 2;
        cc.weightx = 0;
        cc.fill = GridBagConstraints.NONE;
        combos.add(new JLabel("  Background: "), cc);
        cc.gridx = 3;
        cc.weightx = 1.0;
        cc.fill = GridBagConstraints.HORIZONTAL;
        combos.add(subtractCombo, cc);
        // Same preferred width for both, so that equal weights give equal widths
        final int comboWidth = Math.max(signalCombo.getPreferredSize().width, subtractCombo.getPreferredSize().width);
        signalCombo.setPreferredSize(new Dimension(comboWidth, signalCombo.getPreferredSize().height));
        subtractCombo.setPreferredSize(new Dimension(comboWidth, subtractCombo.getPreferredSize().height));
        gc.gridy = 0;
        gc.gridx = 0;
        gc.gridwidth = 5;
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(combos, gc);
        gc.gridwidth = 1;
        gc.insets.top = 4; // small vertical spacer between rows
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
        gc.anchor = GridBagConstraints.EAST; // flush with the options button below, which is wider
        panel.add(resetButton, gc);
        // Row 2: toolbar with [status ... glue ... Options], as in the other cards
        gc.insets.top = 0; // vertical spacer no longer needed
        gc.gridy++;
        gc.gridx = 0;
        gc.gridwidth = 5;
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.CENTER;
        final JToolBar bottomBar = new JToolBar();
        bottomBar.setFloatable(false);
        bottomBar.setOpaque(false);
        bottomBar.setBorder(BorderFactory.createEmptyBorder());
        // Lets the status shrink (and clip) rather than widening the card when the message is long
        statusLabel.setMinimumSize(new Dimension(0, statusLabel.getPreferredSize().height));
        bottomBar.add(statusLabel);
        bottomBar.add(Box.createHorizontalGlue());
        panel.add(bottomBar, gc);

        final JPopupMenu optionsMenu = new JPopupMenu();
        final JMenuItem scriptItem = new JMenuItem("Unmix Full Volume...", IconFactory.menuIcon(IconFactory.GLYPH.CODE));
        if (!owner.getDatasetPath(dataset).isEmpty()) {
            scriptItem.setToolTipText("<html>Generates a Groovy script that applies the current unmixing<br>"
                    + "(channels, weight and B&C ranges) to the full dataset.<br>"
                    + "Run it offline, e.g., in Fiji's Script Editor.");
            scriptItem.addActionListener(e -> {
                final double w = weightSlider.getValue() / 100.0;
                if (w <= 0) {
                    GuiUtils.errorPrompt("Unmixing is disabled. Set a non-zero weight first.");
                    return;
                }
                final int sigIdx = signalCombo.getSelectedIndex();
                final int subIdx = subtractCombo.getSelectedIndex();
                if (sigIdx == subIdx) {
                    GuiUtils.errorPrompt("Signal and background channels must differ.");
                    return;
                }
                try {
                    final String script = generateUnmixingScript(dataset, group, sigIdx, subIdx, w,
                            channelNames[sigIdx], channelNames[subIdx]);
                    ScriptInstaller.newScript(script,
                            String.format("Unmix_%s_minus_%s.groovy", channelNames[sigIdx], channelNames[subIdx]));
                } catch (final java.io.IOException | IllegalStateException ex) {
                    GuiUtils.errorPrompt("Script could not be generated: " + ex.getMessage());
                }
            });
        } else {
            scriptItem.setEnabled(false);
            scriptItem.setToolTipText("Only available for datasets loaded from a file (BDV XML, IMS, N5 or OME-Zarr)");
        }
        optionsMenu.add(scriptItem);
        optionsMenu.addSeparator();
        final JMenuItem helpItem = new JMenuItem("Help...", IconFactory.menuIcon(IconFactory.GLYPH.QUESTION));
        helpItem.addActionListener(e -> GuiUtils.openURL("placeholder url"));
        optionsMenu.add(helpItem);
        bottomBar.addSeparator();
        bottomBar.add(GuiUtils.Buttons.OptionsButton(IconFactory.GLYPH.OPTIONS, 1f, optionsMenu));
        weightSlider.setEnabled(false);
        return panel;
    }

    // Helpers

    /**
     * Generates a Groovy script for full-volume channel unmixing by loading
     * the {@code ChannelUnmixing.groovy} template and replacing placeholders.
     */
    private String generateUnmixingScript(final Object dataset,
                                          final ChannelGroup group,
                                          final int sigIdx, final int subIdx,
                                          final double weight,
                                          final String sigName, final String subName)
            throws java.io.IOException {
        final String filePath = owner.getDatasetPath(dataset);
        final int nTimepoints;
        if (dataset instanceof AbstractSpimData<?> spimData)
            nTimepoints = spimData.getSequenceDescription().getTimePoints().size();
        else if (dataset instanceof SpimDataUtils.N5Sources n5)
            nTimepoints = n5.numTimepoints();
        else
            nTimepoints = 1;

        // Same display-range parameters as UnmixEngine.mix() so that offline output matches the viewer
        final var csSig = group.channelSetup(sigIdx);
        final var csSub = group.channelSetup(subIdx);
        final double sigMin = csSig.getDisplayRangeMin();
        final double subMin = csSub.getDisplayRangeMin();
        final double subRange = csSub.getDisplayRangeMax() - subMin;
        final double rangeScale = (subRange > 0) ? (csSig.getDisplayRangeMax() - sigMin) / subRange : 1.0;

        final java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("INPUT_PATH", filePath);
        values.put("SIG_CHANNEL", sigIdx);
        values.put("SUB_CHANNEL", subIdx);
        values.put("N_CHANNELS", group.size());
        values.put("WEIGHT", String.format(java.util.Locale.US, "%.4f", weight));
        values.put("SIG_MIN", sigMin);
        values.put("SUB_MIN", subMin);
        values.put("RANGE_SCALE", rangeScale);
        values.put("N_TIMEPOINTS", nTimepoints);
        values.put("SIG_NAME", sigName);
        values.put("SUB_NAME", subName);
        return ScriptInstaller.fillRecipe("ChannelUnmixing.groovy", values);
    }
}
