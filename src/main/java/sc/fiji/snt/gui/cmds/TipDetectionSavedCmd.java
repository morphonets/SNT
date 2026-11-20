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


package sc.fiji.snt.gui.cmds;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.TipPalette;
import sc.fiji.snt.tracing.auto.SavedTipDetection;

/**
 * Detects tips in the whole volume using parameters saved by the {@link TipDetectionWizardCmd}, so that no training
 * paths are needed. Detections are added to the Seed Manager.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, initializer = "init", label = "Detect Tips with Saved Parameters...")
public class TipDetectionSavedCmd extends TipDetectionWizardCmd {

    @Override
    public void run() {
        if (isCanceled()) return;
        if (snt == null || !snt.accessToValidImageData()) {
            error("This option requires valid image data to be loaded.");
            return;
        }
        final File dir = snt.getPrefs().getTipDetectionDir();
        final List<SavedTipDetection> saved = SavedTipDetection.list(dir);
        if (saved.isEmpty()) {
            error("No saved parameters found. They are saved when the <i>Tip Detection Wizard</i> is used to detect "
                    + "tips in the whole volume (and the SNT workspace is available).");
            return;
        }
        final Map<String, SavedTipDetection> choices = new LinkedHashMap<>();
        for (final SavedTipDetection s : saved) {
            String label = s.label();
            for (int i = 2; choices.containsKey(label); i++) label = s.label() + " #" + i;
            choices.put(label, s);
        }
        final String[] labels = choices.keySet().toArray(new String[0]);
        final String picked = GuiUtils.callOnEDT(() -> new GuiUtils(ui).getChoice(
                "Detect tips in the whole volume using the parameters of:", "Saved Tip Detection Parameters", labels,
                labels[0]));
        if (picked == null) {
            resetUI();
            return;
        }
        final SavedTipDetection chosen = choices.get(picked);
        final String image = snt.getImageFilenamePrefix();
        final StringBuilder warn = new StringBuilder();
        if (!chosen.image().equals(image))
            warn.append("The parameters were learned on <i>").append(chosen.image()).append("</i>, but the current image is <i>")
                    .append(image).append("</i>. The min. score depends on image intensity. ");
        if (chosen.channel() != snt.getChannel())
            warn.append("They were learned on channel ").append(chosen.channel()).append(", but channel ")
                    .append(snt.getChannel()).append(" is active. ");
        if (!warn.isEmpty() && !getConfirmationEdtSafe(warn + "Continue?", "Different Data")) {
            resetUI();
            return;
        }
        run(chosen.config(), true, new TipPalette.ParseOptions(chosen.level(), chosen.skipEmpty(),
                chosen.deviationFactor(), false), null);
    }
}
