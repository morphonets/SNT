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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package sc.fiji.snt.gui;

import java.util.List;

import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.SeedManager;
import sc.fiji.snt.SeedManager.Detector;
import sc.fiji.snt.gui.StepWizard.Step;
import sc.fiji.snt.util.SNTColor;

/**
 * Helps users to find the seed detector that suits their goal: asks what is to be detected, refines the choice where
 * several detectors apply, checks that the detector's requirements are met, and runs it, as the Seed Manager's
 * detection menu would. Detected features are added as seeds
 *
 * @author Tiago Ferreira
 * @see SeedManager#run(Detector)
 */
public final class SeedDetectionWizard {

    private enum Target {SOMAS, TIPS, TUFTS, ROOTS, SWELLINGS}

    private static StepWizard instance;

    private SeedDetectionWizard() {
    }

    /** What the user has chosen so far */
    private static final class State {
        Target target = Target.SOMAS;
        int refinement; // index of the choice of the second step
    }

    /**
     * Shows the wizard, or brings it to front if it is already open
     *
     * @param ui          the SNT UI
     * @param seedManager the Seed Manager that runs the detectors
     */
    public static synchronized void show(final SNTUI ui, final SeedManager seedManager) {
        if (instance != null && instance.isDisplayable()) {
            instance.toFront();
            return;
        }
        final State st = new State();

        // Step 1: goal
        final Step goal = new Step("Structures")
                .body("Which type of structure would you like to detect? Detected features are added as seeds "
                        + "(Seeds tab).");
        goal.choices(i -> {
            st.target = Target.values()[i];
            st.refinement = 0;
        }, "Cell bodies (somata)", "Tips of neurites or axon terminals",
                "Tufts (clusters of nearby terminal branches)",
                "Shared origins of disconnected tracings (roots)",
                "Swellings, boutons, varicosities or puncta along traced paths");

        // Step 2: refinement (only where several detectors apply)
        final Step method = new Step("Method")
                .applicableIf(() -> st.target == Target.TIPS || st.target == Target.SWELLINGS);
        method.onEnter(w -> {
            if (st.target == Target.TIPS) {
                method.body("Tip detection learns its parameters from training paths. Have you already detected "
                        + "tips in similar images and saved the parameters?");
                method.choices(i -> st.refinement = i, "No: learn the parameters from training paths (interactive)",
                        "Yes: use the saved parameters");
            } else {
                method.body("Swellings can be detected from two kinds of information:");
                method.choices(i -> st.refinement = i,
                        "The <b>radius profile</b> of traced paths (requires fitted radii)",
                        "<b>Intensity maxima</b> around traced paths (requires the image)");
            }
            method.select(st.refinement);
        });

        // Step 3: requirements and launch
        final Step run = new Step("Detector");
        run.onEnter(w -> {
            run.body(description(detector(st)));
            run.status(readiness(ui, seedManager, detector(st)));
        });
        run.poll(w -> run.status(readiness(ui, seedManager, detector(st))));
        run.advanceIf(() -> seedManager.requirementProblem(detector(st)) == null);
        run.footer("What happens next?", "Detected features are listed in the Seeds tab, where they can be "
                + "inspected, filtered by score, and removed. Seeds can then be used to trace: as roots of trees "
                + "(somas), as endpoints (tips), or as waypoints (see the <i>Seeds as...</i> menu).<br>"
                + "<b>Tip:</b> if you already know the detector you need, run it directly from the <i>+</i> menu "
                + "in the Seeds tab.");
        run.finish("Finish & Open Detector...", IconFactory.GLYPH.SEARCH, w -> {
            ui.selectTab("Seeds");
            seedManager.run(detector(st));
        });

        instance = new StepWizard(ui, "Guided Feature Detection", List.of(goal, method, run));
        instance.setVisible(true);
    }

    private static Detector detector(final State st) {
        return switch (st.target) {
            case SOMAS -> Detector.SOMAS;
            case TUFTS -> Detector.TUFTS;
            case ROOTS -> Detector.ROOTS;
            case TIPS -> (st.refinement == 0) ? Detector.TIPS : Detector.TIPS_SAVED;
            case SWELLINGS -> (st.refinement == 0) ? Detector.SWELLINGS : Detector.MAXIMA;
        };
    }

    private static String readiness(final SNTUI ui, final SeedManager seedManager, final Detector d) {
        final String problem = seedManager.requirementProblem(d);
        if (problem != null) {
            return "<font color='" + SNTColor.colorToString(GuiUtils.Colors.errorColor()) + "'>"
                    + GuiUtils.Text.escapeHtml(problem) + "</font>";
        }
        final StringBuilder sb = new StringBuilder("Requirements are met.");
        if (d.needsPaths()) {
            final List<Path> selected = ui.getPathManager().getSelectedPaths(false);
            if (selected != null && !selected.isEmpty()) {
                sb.append(" The ").append(selected.size()).append(" path(s) selected in Path Manager will be scanned.");
            } else {
                sb.append(" All ").append(ui.getPathManager().getSelectedPaths(true).size())
                        .append(" path(s) in Path Manager will be scanned (select some to restrict the scan).");
            }
        }
        return sb.toString();
    }

    private static String description(final Detector d) {
        return switch (d) {
            case SOMAS -> "<b>Soma detection</b> finds cell bodies in the image and adds one seed per soma. Seeds "
                    + "can later be used as the roots of trees when tracing several cells.";
            case TIPS -> "The <b>tip detection wizard</b> detects the tips of neurites and other ramified "
                    + "structures in large volumes. It learns the detection parameters from paths that you provide, "
                    + "and can save them for re-use.";
            case TIPS_SAVED -> "<b>Tip detection with saved parameters</b> detects tips in the whole volume using "
                    + "parameters saved earlier by the tip detection wizard, without training paths.";
            case TUFTS -> "<b>Tuft detection</b> finds clusters of nearby terminal branches in the traced "
                    + "structure. It requires traced paths.";
            case ROOTS -> "<b>Root detection</b> finds disconnected tracings that start close together, as if "
                    + "branching from a shared soma that was not imaged. It requires traced paths.";
            case SWELLINGS -> "<b>Swelling detection</b> finds boutons and varicosities along traced paths from "
                    + "their radius (and, optionally, intensity) profiles. The paths need fitted radii "
                    + "(<i>Refine</i> menu).";
            case MAXIMA -> "<b>Maxima detection</b> finds intensity maxima (varicosities, spines, puncta) in "
                    + "annular cross-sections around traced paths. It requires traced paths and the image.";
        };
    }

}
