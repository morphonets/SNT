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

package sc.fiji.snt.plugin;

import net.imagej.ImgPlus;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.Interactive;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.cmds.AutoTraceConfigDialog;
import sc.fiji.snt.tracing.auto.AutoTraceConfig;
import sc.fiji.snt.util.ImgUtils;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Interactive command providing a GUI for GWDT autotracing when an image is
 * already loaded in SNT. Implements {@link Interactive} so that the dialog
 * stays open and responsive; the actual tracing is triggered via a "Run"
 * button callback rather than the standard OK button.
 *
 * @author Tiago Ferreira
 * @see GWDTTracerCommonCmd
 * @see GWDTTracerFileCmd
 */
@Plugin(type = Command.class, initializer = "init")
public class GWDTTracerCmd extends GWDTTracerCommonCmd implements Interactive {

    private static final String PROMPT_TITLE = "Autotracing Grayscale Data (GWDT)... "; // MUST BE UNIQUE for getPrompt() to work

    @Parameter(label = "Learn Parameters From Selected Path(s)...", callback = "learnFromSelectedPaths",
            description = "<HTML>Derive tracing parameters from paths currently selected<br>" +
                    "in Path Manager. Radii, intensity, and branch geometry are<br>" +
                    "sampled to auto-fill settings above.")
    private Button learnFromSelectedPathsBtn;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String learnFromExampleMsg = " ";

    @Parameter(label = "   Run   ", callback = "runTrace", description = "<HTML>Run autotracing")
    private Button run;

    private JDialog prompt;
    private AutoTraceConfigDialog configDialog;

    @SuppressWarnings("unused")
    private void init() {
        getInfo().setLabel(PROMPT_TITLE);
        initForImage();
    }

    /**
     * Returns whether an instance of this dialog is currently visible.
     * Used by SNTUI to enforce singleton behavior.
     *
     * @return true if a dialog with this command's title is showing
     */
    public static boolean isOpen() {
        for (final Window w : JDialog.getWindows()) {
            if (w instanceof JDialog && w.isVisible() && PROMPT_TITLE.equals(((JDialog) w).getTitle())) {
                w.toFront();
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean isFileMode() {
        return false;
    }

    private JDialog getPrompt() {
        // HACK: See ComputeSecondaryImg#getPrompt()
        if (prompt == null) {
            for (final Window w : JDialog.getWindows()) {
                if (w instanceof JDialog && PROMPT_TITLE.equals(((JDialog) w).getTitle())) {
                    prompt = ((JDialog) w);
                }
            }
        }
        return prompt;
    }

    @Override
    public void run() {
        // Called every time a widget changes in the prompt: Do nothing by default.
        // The actual run is triggered by the runTrace() callback.
    }

    @SuppressWarnings("unused")
    private void runTrace() {
        if (configDialog != null) configDialog.dispose();
        if (getPrompt() != null) prompt.dispose();
        // Button callbacks in Interactive commands run on the EDT,
        // so we must offload the heavy tracing work to a background
        // thread; otherwise setCanvasLabelAllPanes() can never repaint.
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                runCommand();
                return null;
            }
        }.execute();
    }

    @SuppressWarnings("unused")
    private void learnFromSelectedPaths() {
        final Collection<Path> selected = sntService.getSelectedPaths();
        if (selected == null || selected.isEmpty()) {
            learnFromExampleMsg = "<HTML><font color='red'>No paths selected in Path Manager.</font>";
            return;
        }

        try {
            final ImgPlus<?> chosenImp = getImgFromImgChoice();
            if (chosenImp == null) {
                learnFromExampleMsg = "<HTML><font color='red'>No valid image available.</font>";
                return;
            }

            @SuppressWarnings("unchecked")
            final RandomAccessibleInterval<? extends RealType<?>> source =
                    (RandomAccessibleInterval<? extends RealType<?>>) chosenImp;
            final double[] spacing = ImgUtils.getSpacing(chosenImp);

            final AutoTraceConfig config = AutoTraceConfig.fromPaths(new ArrayList<>(selected), source, spacing);

            // Show the config dialog
            configDialog = new AutoTraceConfigDialog(getPrompt(), config);
            configDialog.setApplyCallback(cfg -> applySelectedConfig(configDialog, cfg));
            configDialog.setVisible(true);

            learnFromExampleMsg = "<HTML><font color='green'>Learned from "
                    + selected.size() + " path(s). Review the configuration window.</font>";

            SNTUtils.log("AutoTraceConfig derived:\n" + config.getSummary());

        } catch (final Exception ex) {
            learnFromExampleMsg = "<HTML><font color='red'>Error: " + ex.getMessage() + "</font>";
            SNTUtils.log("AutoTraceConfig error: " + ex.getMessage());
        }
    }

    private void applySelectedConfig(final AutoTraceConfigDialog dialog, final AutoTraceConfig config) {
        if (dialog.isSelected("backgroundThreshold") && !Double.isNaN(config.getBackgroundThreshold())) {
            backgroundThreshold = config.getBackgroundThreshold();
        }
        if (dialog.isSelected("lengthThreshold") && !Double.isNaN(config.getLengthThreshVoxels())) {
            lengthThreshold = config.getLengthThreshVoxels();
        }
        if (dialog.isSelected("branchTuneMaxAngle") && !Double.isNaN(config.getBranchTuneMaxAngle())) {
            branchTuneMaxAngle = config.getBranchTuneMaxAngle();
        }
        if (dialog.isSelected("scoreMapEnabled") && config.getScoreMapScales() != null) {
            scoreMapFilter = SCORE_MAP_TUBENESS;
        }
        learnFromExampleMsg = "<HTML><font color='green'>Applied selected parameters.</font>";
        SNTUtils.log("AutoTraceConfig: applied selected parameters to dialog");
    }
}
